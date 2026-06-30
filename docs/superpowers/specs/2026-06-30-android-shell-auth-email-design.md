# Android Shell + Auth + Email (Design Spec)

**Date:** 2026-06-30
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / sub-project 2 (the WWT Android app shell, native login, and the first sub-app — email — via the hosted SPA)

## 1. Context & goal

Second sub-project of the WWT mobile foundation (overall design:
`docs/superpowers/specs/2026-06-30-wwt-mobile-foundation-design.md`). Sub-project 1
(backend bearer auth + push registry/fan-out) shipped as PR #24 on
`email-client-maileroo` and is the dependency this builds on.

This delivers the **WWT native Android shell**: one Kotlin app (the general WWT
app, not a mail-specific app) that shows a native login gating the whole shell,
then hosts WWT **sub-apps** in a hardened WebView. **Mail is sub-app #1**; later
sub-apps (chat, CRM, issues, learning, projects) are added to the same shell as
additional registry entries. This sub-project establishes the shell, auth, and
WebView patterns every sub-app reuses.

Because there is only one sub-app today, the shell auto-opens it after login; a
launcher/switcher UI is deliberately deferred until there are two or more sub-apps
(see §2). The app is the new `mobile-app` repo. The mail backend at
`https://mail.whitewolf.tech` is unchanged by this sub-project.

## 2. Scope

**In scope:** the WWT app shell with a **sub-app registry** (one entry — mail —
today); native login gating the shell; authenticate via `POST /api/login`; store the
bearer token (Android Keystore) for native use; seed the WebView session cookie so
the sub-app loads authenticated; hardened WebView hosting the active sub-app; the
shell auto-opening the sole sub-app; native loading/error/retry states; sign-out.

**Out of scope (later sub-projects / when warranted):** a launcher/switcher UI
between sub-apps (deferred until 2+ sub-apps exist); any actual second sub-app
(chat/CRM/etc.); UnifiedPush client + wake-to-sync; Obtainium distribution + APK
signing; app-grade mobile-first polish of the email web UI; OIDC. The JS↔native
bridge is intentionally minimal here (no JS bridge is required for this sub-project —
see §4) and grows when push needs it.

## 3. Why this auth model

A WebView cannot attach an `Authorization: Bearer` header to the SPA's own
`fetch`/XHR requests, but it fully supports cookies. So the two credentials the
backend issues serve two different consumers:

- The **SPA inside the WebView** authenticates via the **session cookie** — its
  existing, unchanged mechanism.
- The **native shell** holds the **bearer token** for native-initiated API calls
  (none in this sub-project; the push sub-project is the first consumer).

Login is therefore **native-driven and gates the whole shell** (one login for all
WWT sub-apps — consistent with the SSO direction): a native screen calls
`POST /api/login` (which, per PR #24, returns `{ok, token, expires}` *and* sets the
`session` cookie). On success the shell stores the token in the Keystore and seeds
the WebView `CookieManager` with the session cookie, then opens the active sub-app
already authenticated. No SPA changes are required. (Today all sub-apps share the
mail backend's auth; when sub-apps span hosts, this is where OIDC slots in.)

## 4. Architecture & components

Kotlin + Jetpack Compose. Single `Activity` that shows the login screen or the
active sub-app's WebView screen based on auth state. Units with clear boundaries:

- **`SubApp` / `SubAppRegistry`** — a `SubApp` is `(id, title, url)`; the registry
  is the ordered list of WWT sub-apps, with **mail as the only entry** now
  (`SubApp("mail", "Mail", BuildConfig.MAIL_BASE_URL)`). Interface: `all(): List<SubApp>`,
  `default(): SubApp` (the sub-app the shell auto-opens — the sole entry today).
  Adding a future sub-app is one new entry here. Depends on: `BuildConfig`.
- **`TokenStore`** — persists/clears the bearer token + expiry in
  `EncryptedSharedPreferences` (Android Keystore-backed). Interface:
  `save(token: String, expiresUnix: Long)`, `token(): String?` (null if absent or
  expired), `expiresAt(): Long?`, `clear()`. Depends on: Android Keystore.
- **`SessionCookie`** — seeds/clears the WebView `CookieManager` for the backend
  host. Interface: `seed(setCookieHeader: String)` (or `seed(name, value)`),
  `clear()`. Depends on: `android.webkit.CookieManager`.
- **`AuthRepository`** — orchestrates login. Interface:
  `login(email, password): Result<Unit>` → POSTs `/api/login`, on 200 parses
  `{token, expires}`, calls `TokenStore.save`, extracts the `Set-Cookie: session=…`
  header and calls `SessionCookie.seed`; maps 401 → invalid-credentials error,
  other failures → network/server error. `isLoggedIn(): Boolean` =
  `TokenStore.token() != null`. `logout()` → `TokenStore.clear()` +
  `SessionCookie.clear()`. Depends on: `TokenStore`, `SessionCookie`, an HTTP
  client (OkHttp).
- **`LoginViewModel`** — drives the login screen: holds email/password/loading/error
  state, calls `AuthRepository.login`, exposes a success event. Depends on:
  `AuthRepository`.
- **Login screen (Compose)** — email + password fields, submit button, inline error,
  loading indicator. Depends on: `LoginViewModel`.
- **`SubAppWebView`** — a hardened WebView (wrapped for Compose) that loads a given
  `SubApp.url` (the active sub-app — mail today). Configuration: JavaScript enabled,
  DOM storage enabled; file access disabled (`allowFileAccess=false`,
  `allowContentAccess=false`, `allowFileAccessFromFileURLs=false`,
  `allowUniversalAccessFromFileURLs=false`); Safe Browsing enabled; mixed content
  disallowed. A `WebViewClient` that: allows navigation only to URLs whose host
  equals the active sub-app's host; routes any other (external) link to the system
  browser / Custom Tab; surfaces load failures to a native error state. Depends on:
  `SessionCookie` already seeded, `SubApp`.
- **Shell screen / nav** — chooses login vs sub-app from `AuthRepository.isLoggedIn()`
  at start; when logged in, opens `SubAppRegistry.default()` (the sole sub-app today —
  no launcher UI yet); shows native loading while the WebView first paints; shows a
  native retry screen on network failure; provides a minimal sign-out affordance that
  calls `AuthRepository.logout()` and returns to login.

No JS↔native bridge (`@JavascriptInterface`) is added in this sub-project — auth is
native and cookie-seeded, email is the SPA as-is, and external links are handled by
the `WebViewClient`. The bridge is introduced by the push sub-project.

## 5. Configuration

- **Application ID `tech.whitewolf.app`** — the general WWT app (one install, one
  identity hosting all sub-apps), not a mail-specific ID. Display name: "WWT" (full
  "White Wolf Technology" where space allows).
- **Sub-app URLs via `BuildConfig`** — mail's URL is `BuildConfig.MAIL_BASE_URL`
  (release `https://mail.whitewolf.tech`; debug build may override it via a
  `gradle.properties`/build-type value for local testing). Each `SubApp`'s host is
  derived from its own URL (used for cookie seeding and that sub-app's same-host
  navigation check). Future sub-apps each get their own `BuildConfig` URL constant +
  registry entry.
- `minSdk 29` (Android 10). `targetSdk` = latest stable (36 at time of writing).
  Kotlin + Jetpack Compose.
- Permissions: `INTERNET` only in this sub-project (`POST_NOTIFICATIONS` arrives
  with the push sub-project).

## 6. Error handling

- Invalid credentials (`401` from `/api/login`) → inline error on the login screen;
  no token stored.
- Network/timeout on login → inline "couldn't reach the server" error with retry.
- A valid stored token at startup → skip login and load the WebView. An expired
  token → treated as logged-out (login screen). (Token expiry is checked locally via
  the stored `expires`; the cookie and token share the same 7-day lifetime.)
- WebView load failure (offline, DNS, TLS) → native retry screen, never a blank/
  broken white page.
- The SPA returning to its own login (e.g. the session cookie was rejected/expired)
  → out of this sub-project's guaranteed handling beyond the cookie having the same
  lifetime as the token; a `401`-driven re-login refinement can come with the push
  sub-project when the bridge exists. (Noted, not silently assumed.)

## 7. Component boundaries (isolation/testability)

`TokenStore`, `SessionCookie`, `AuthRepository`, and `LoginViewModel` are plain
Kotlin units testable without an Activity. `AuthRepository` is tested against an
OkHttp `MockWebServer` (real HTTP parsing, no Android). The WebView configuration
and navigation routing are the only parts needing an instrumented test. The
backend HTTP contract (`/api/login` → `{token, expires}` + `Set-Cookie`) is the
seam between this app and PR #24.

## 8. Testing approach

- **Unit (JVM):** `TokenStore` save/load/expiry/clear (with a fake/in-memory backing
  to avoid Keystore in unit tests; an instrumented test covers the real Keystore
  path). `AuthRepository.login` against `MockWebServer`: 200 → token saved + cookie
  seeded; 401 → invalid-credentials, nothing saved; 5xx/network → error. `SessionCookie`
  parses a `Set-Cookie` header into the right `CookieManager` call.
  `LoginViewModel` state transitions (idle → loading → success/error).
- **Instrumented (device/emulator):** real `EncryptedSharedPreferences` round-trip;
  WebView loads the mail sub-app URL (`MAIL_BASE_URL`); an external link (different
  host) is routed out rather than loaded in-app; same-host navigation stays in-app.
- **Manual:** log in on a device against `mail.whitewolf.tech`, confirm the inbox
  renders, send a test mail, kill/relaunch (stays logged in), sign out (returns to
  login, cookie cleared).

## 9. Build sequencing (rough)

(1) Gradle scaffold (Compose, application ID `tech.whitewolf.app`,
`BuildConfig.MAIL_BASE_URL`, INTERNET, SDKs) → (2) `SubApp`/`SubAppRegistry` (mail
entry) → (3) `TokenStore` → (4) `SessionCookie` → (5) `AuthRepository` +
`LoginViewModel` → (6) Login screen (Compose) → (7) hardened `SubAppWebView` +
same-host/external-link routing → (8) shell nav (login ↔ auto-opened sub-app by auth
state) + loading/error/retry + sign-out.
