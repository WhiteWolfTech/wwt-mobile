# WWT Mobile App — Foundation (Design Spec)

**Date:** 2026-06-30
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation (app shell + auth + email + push + self-update)

## 1. Context & goal

White Wolf Technology (WWT, domain `whitewolf.tech`) wants a mobile app — primarily
for GrapheneOS, also running on stock-Android Pixels — that is the front door to
company systems. It will grow into a suite: email first, then issue tracking, chat,
learning, CRM, projects (including open source).

This spec covers only the **foundation sub-project**, which delivers the reusable
shell plus the first capability (email) and the two cross-cutting concerns the whole
suite depends on (push, self-update). Later modules each get their own spec → plan →
build cycle and reuse this foundation.

Guiding constraints: **minimal permissions**, **no Google dependency**, **best
achievable UX**, and a **good self-update mechanism**.

## 2. Existing system (not built here)

A Go backend already exists (`github.com/PeterRounce/email-client-maileroo`):

- Single static Go binary, pure-Go SQLite, embedded React/Vite SPA. Multi-user.
- **Sends** via the Maileroo v2 API; **receives** via Maileroo inbound webhooks
  (`POST /webhooks/inbound`); stores messages to SQLite with threading and search.
- Clean JSON API behind session-cookie auth: `POST /api/login`, `POST /api/logout`,
  `GET /api/threads`, `GET /api/threads/{id}`, `POST /api/threads/{id}/seen`,
  `GET /api/messages/{id}/body`, attachments, `POST /api/send`, `GET /api/search`,
  `GET/PATCH /api/me`, plus `/api/admin/*` and `GET/POST /api/setup`.
- Session token is already a portable HMAC bearer string `userID.exp.sig`
  (`internal/auth/session.go`), currently delivered only as a `session` cookie.
- Deployed directly on a host; Caddy terminates TLS and reverse-proxies to
  `127.0.0.1:8080`.

Maileroo is the email send/inbound **provider**; this backend is the multi-client
**sync server**. The mobile app is a new client of its API — it does not talk to
Maileroo directly.

## 3. Architecture

A thin **native Kotlin shell** hosts the existing React SPA (and future web modules)
inside a **hardened WebView**, connected by a small JS↔native bridge.

- **The web layer owns all UI.** The same responsive web app serves desktop browsers
  and the mobile shell. Building each future module once as web makes it appear in the
  app for free.
- **The native shell owns only what native does best:** secure token storage, push,
  self-update, and share/intent integration.
- **Escape hatch:** any single screen can be promoted to a native implementation
  inside the same shell later if its UX provably can't meet the bar as web. This does
  not require rebuilding the other modules.
- **One signed APK** runs identically on GrapheneOS and stock-Android Pixel. No
  GrapheneOS-only APIs are used.

"Best achievable UX" is pursued by making the web UI genuinely **mobile-first /
app-grade** (virtualized lists, touch/swipe gestures, offline via service worker,
optimistic send) rather than by going native — that investment also benefits desktop.

### JS↔native bridge (the full native surface, ~5 methods)

1. **Token storage** — get / set / clear the auth token (Android Keystore-backed).
2. **Push** — register a UnifiedPush endpoint; deliver incoming wake-up payloads to
   the web layer.
3. **Share / open-external-link** — hand off to the OS share sheet / browser.
4. **App info** — current version; trigger an update check (delegates to Obtainium).
5. *(optional later)* biometric unlock.

## 4. Backend changes required (small)

1. **Bearer-token auth.** Return the existing signed token in the `POST /api/login`
   response body, and make `auth.Middleware` accept the token from an
   `Authorization: Bearer <token>` header in addition to the existing cookie. No new
   token format — the current `userID.exp.sig` scheme is reused unchanged.
2. **Push registration + fan-out.**
   - Store `(user_id, unifiedpush_endpoint)` registrations (new table).
   - Endpoints to register/unregister an endpoint for the authenticated user.
   - On new inbound mail for a user (in the existing inbound webhook path), POST a
     **data-light wake-up** to each of that user's endpoints.

These are the only backend changes in this sub-project.

## 5. Authentication

- App obtains the signed token by `POST /api/login` with credentials, stores it in the
  **Android Keystore**, and sends it as `Authorization: Bearer` on every API request.
  The shell also injects it into WebView requests so the hosted web app is
  authenticated.
- **OIDC is deferred** (central SSO remains the long-term identity direction for the
  suite). The design boundary that makes this clean: *the app holds a bearer token and
  sends it; how the token is obtained is swappable.* Adding OIDC later changes only the
  token-issuance step (system browser → authorization code + PKCE → token exchange;
  backend becomes a resource server). Token storage, the `Authorization` header, and
  all API calls stay identical — no app rework.

## 6. Push notifications (UnifiedPush)

- **Self-hosted ntfy** as the push server, on WWT infra — push traffic stays on WWT
  systems, no third party, no Google.
- **Standard UnifiedPush distributor** model for v1: the user installs a distributor
  app (ntfy, from F-Droid) once; one shared connection wakes all UnifiedPush apps.
  The WWT app needs **no foreground service** and stays minimal-permission.
- **Data-light wake-up pushes:** the push carries no email content (at most
  sender/subject) — just a signal. The app then fetches new mail over the
  authenticated API. Email content never traverses the push path.
- Flow: app registers with the distributor → gets an endpoint URL → sends it to the
  backend → backend POSTs a wake-up to that URL on new inbound mail → distributor
  wakes the app → app syncs.

**Future option (not in v1):** an **embedded distributor** (the app holds its own push
connection, no separate app to install) can be added if non-technical users need a
self-contained experience. It costs a persistent foreground service + boot-restart
permission, so it is deliberately out of v1 to keep permissions minimal. The push
abstraction should not assume the standard distributor in a way that blocks this.

## 7. Distribution & self-update

- **Obtainium**, pointed at a **private** signed-APK source (a private Git forge's
  releases with a PAT, or the WWT Caddy host serving the APK behind an `Authorization`
  header). No public repo required.
- Background updates use the standard Android installer path (installer-of-record +
  "install unknown apps" granted to Obtainium + `setRequireUserAction(false)` on
  Android 12+) — identical on GrapheneOS and stock Pixel. The **WWT app itself holds no
  install permission**; Obtainium does.
- APKs are signed by a WWT signing key (one key for the suite). Key management and
  reproducible builds are an implementation-plan concern.

**Noted alternative (not chosen):** a self-hosted F-Droid repo scales better for a
large suite (subscribe-once central catalog vs. per-app-per-device sources), and the
*same* signed APKs can be published to it later without disrupting Obtainium users.
Obtainium was chosen for v1 for its lower server-side effort and existing familiarity.

## 8. Permissions

- **v1 target:** `INTERNET`, `POST_NOTIFICATIONS` (Android 13+). Nothing else.
- Embedded-distributor mode (future, optional) would additionally require a
  foreground-service type + boot-restart permission — only if that mode is enabled.

## 9. Component boundaries (for isolation/testability)

- **Backend / auth** — issues + validates the bearer token. Interface: `POST /api/login`
  returns token; `Authorization: Bearer` accepted everywhere `/api/` is.
- **Backend / push registry + fan-out** — owns endpoint storage and wake-up POSTs.
  Interface: register/unregister endpoints; fires wake-ups on inbound mail.
- **Native shell** — WebView host + JS bridge + token storage + push client + update
  hook. Interface: the ~5 bridge methods in §3.
- **Web app (existing SPA)** — all UI; consumes the bridge for token/push/share;
  consumes the backend JSON API. Made mobile-first/app-grade.
- **ntfy server** — push transport. Interface: endpoint URLs the backend POSTs to.

Each unit is independently understandable and testable; the bridge and the HTTP API
are the well-defined seams between them.

## 10. Build sequencing (rough)

1. **Backend**: bearer-token auth + push registration/fan-out (with tests).
2. **Kotlin shell**: hardened WebView + token bridge + Keystore storage + login.
3. **Push**: UnifiedPush standard distributor registration + wake-to-sync.
4. **Distribution**: Obtainium release pipeline (signing key, private source).
5. **UX**: app-grade mobile polish on the email web UI (virtualized lists, gestures,
   offline, optimistic send).

## 11. Out of scope (this sub-project)

- OIDC / identity provider (Authentik/Keycloak) — deferred; designed to drop in later.
- Embedded UnifiedPush distributor — future option, §6.
- Self-hosted F-Droid repo — noted alternative, §7.
- All non-email modules (chat, CRM, issues, learning, projects) — each its own future
  spec, reusing this foundation.
- iOS — not in scope (Android/GrapheneOS-only roadmap).
- Outbound attachments (the existing backend does not support them in this version).

## 12. Testing approach

- **Backend**: Go unit/integration tests for bearer-token acceptance, push
  registration, and inbound→wake-up fan-out (the repo already has a test suite to
  extend).
- **Shell**: instrumented tests for the JS bridge (token round-trip via Keystore,
  push-endpoint delivery to the web layer) and WebView hardening (CSP, no arbitrary
  origins).
- **End-to-end**: send mail via Maileroo inbound → backend stores → wake-up pushed →
  device syncs and notifies; login → token stored → authenticated API call.
- **Distribution**: verify a signed APK installs and updates via Obtainium on both
  GrapheneOS and stock Pixel.
