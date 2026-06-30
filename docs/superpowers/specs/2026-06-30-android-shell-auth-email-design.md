# Android Shell + Auth + Email (Design Spec)

**Date:** 2026-06-30
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / sub-project 2 (the Android app shell, native login, and email via the hosted SPA)

## 1. Context & goal

Second sub-project of the WWT mobile foundation (overall design:
`docs/superpowers/specs/2026-06-30-wwt-mobile-foundation-design.md`). Sub-project 1
(backend bearer auth + push registry/fan-out) shipped as PR #24 on
`email-client-maileroo` and is the dependency this builds on.

This delivers the **native Android shell**: a Kotlin app that shows a native login
screen, authenticates against the backend, and hosts the existing React email SPA
in a hardened WebView so a user can read and send mail. It establishes the shell,
auth, and WebView patterns that later modules reuse.

The app is the new `mobile-app` repo. The backend at `https://mail.whitewolf.tech`
is unchanged by this sub-project.

## 2. Scope

**In scope:** native login screen; authenticate via `POST /api/login`; store the
bearer token (Android Keystore) for native use; seed the WebView session cookie so
the SPA loads authenticated; hardened WebView hosting the SPA; native
loading/error/retry states; sign-out.

**Out of scope (later sub-projects):** UnifiedPush client + wake-to-sync; Obtainium
distribution + APK signing; app-grade mobile-first polish of the email web UI; OIDC;
any non-email module. The JS↔native bridge is intentionally minimal here (no JS
bridge is required for this sub-project — see §4) and grows when push needs it.

## 3. Why this auth model

A WebView cannot attach an `Authorization: Bearer` header to the SPA's own
`fetch`/XHR requests, but it fully supports cookies. So the two credentials the
backend issues serve two different consumers:

- The **SPA inside the WebView** authenticates via the **session cookie** — its
  existing, unchanged mechanism.
- The **native shell** holds the **bearer token** for native-initiated API calls
  (none in this sub-project; the push sub-project is the first consumer).

Login is therefore **native-driven**: a native screen calls `POST /api/login`
(which, per PR #24, returns `{ok, token, expires}` *and* sets the `session`
cookie). On success the shell stores the token in the Keystore and seeds the
WebView `CookieManager` with the session cookie, then loads the SPA already
authenticated. No SPA changes are required.

## 4. Architecture & components

Kotlin + Jetpack Compose. Single `Activity` that shows either the login screen or
the WebView screen based on auth state. Units with clear boundaries:

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
- **`MailWebView`** — a hardened WebView (wrapped for Compose) that loads
  `BuildConfig.BASE_URL`. Configuration: JavaScript enabled, DOM storage enabled;
  file access disabled (`allowFileAccess=false`, `allowContentAccess=false`,
  `allowFileAccessFromFileURLs=false`, `allowUniversalAccessFromFileURLs=false`);
  Safe Browsing enabled; mixed content disallowed. A `WebViewClient` that: allows
  navigation only to URLs whose host equals the backend host; routes any other
  (external) link to the system browser / Custom Tab; surfaces load failures to a
  native error state. Depends on: `SessionCookie` already seeded.
- **Shell screen / nav** — chooses login vs WebView from `AuthRepository.isLoggedIn()`
  at start; shows native loading while the WebView first paints; shows a native
  retry screen on network failure; provides a minimal sign-out affordance that calls
  `AuthRepository.logout()` and returns to login.

No JS↔native bridge (`@JavascriptInterface`) is added in this sub-project — auth is
native and cookie-seeded, email is the SPA as-is, and external links are handled by
the `WebViewClient`. The bridge is introduced by the push sub-project.

## 5. Configuration

- `BuildConfig.BASE_URL` = `https://mail.whitewolf.tech` for release builds; the
  debug build may override it (e.g. via a `gradle.properties`/build-type value) for
  local testing against a dev backend. The backend host is derived from this single
  value (used for cookie seeding and the WebView same-host navigation check).
- Package: `tech.whitewolf.mail`. `minSdk 29` (Android 10). `targetSdk` = latest
  stable (36 at time of writing). Kotlin + Jetpack Compose.
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
  WebView loads `BASE_URL`; an external link (different host) is routed out rather
  than loaded in-app; same-host navigation stays in-app.
- **Manual:** log in on a device against `mail.whitewolf.tech`, confirm the inbox
  renders, send a test mail, kill/relaunch (stays logged in), sign out (returns to
  login, cookie cleared).

## 9. Build sequencing (rough)

(1) Gradle project scaffold (Compose, BuildConfig.BASE_URL, INTERNET, package/SDKs)
→ (2) `TokenStore` → (3) `SessionCookie` → (4) `AuthRepository` + `LoginViewModel`
→ (5) Login screen (Compose) → (6) hardened `MailWebView` + same-host/external-link
routing → (7) shell nav (login↔web by auth state) + loading/error/retry +
sign-out.
