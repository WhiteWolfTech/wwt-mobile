# Push Notifications (UnifiedPush) — Design Spec

**Date:** 2026-07-02
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / sub-project 3 (push notifications for the mail sub-app)

## 1. Context & goal

Deliver new-mail push notifications to the WWT Android app (`tech.whitewolf.app`)
without any Google dependency, using **UnifiedPush** and a **self-hosted ntfy**
server. When mail arrives, the user gets a notification even when the app is
backgrounded/closed; tapping it opens the app to the mailbox.

The backend half already exists (PR #24, merged + deployed): a `push_endpoints`
table, authenticated `POST /api/push/register` + `/api/push/unregister`
(host-pinned via `PUSH_ENDPOINT_HOSTS`, fail-closed), and an inbound-webhook
fan-out that POSTs a **data-light** `{"type":"new_mail"}` wake-up to each
registered endpoint through the SSRF-safe client. This sub-project stands up the
push server, points the backend at it, and builds the Android UnifiedPush client.

## 2. Scope

**In scope:**
- **Infra:** deploy **ntfy** at `https://ntfy.whitewolf.tech`; set
  `PUSH_ENDPOINT_HOSTS=ntfy.whitewolf.tech` on the mail deploy and redeploy.
- **Android UnifiedPush client:** register a distributor endpoint → send it to
  `/api/push/register`; receive wake-ups → post a **generic "New mail"**
  notification; unregister on sign-out; request `POST_NOTIFICATIONS`.
- **Onboarding runbook:** install the ntfy distributor app **via Obtainium**,
  point it at `ntfy.whitewolf.tech`.

**Out of scope (deferred / not needed):**
- **Rich notifications** (sender/subject/count) — v1 is a generic alert; no native
  API fetch of mail content.
- **Embedded distributor** (app holds its own push connection) — standard
  distributor only; embedded stays deferred (it needs a foreground service).
- **JS↔native bridge** — not required for v1 (native does push→notify→open; the SPA
  shows the inbox when opened).
- **No backend code change** — PR #24's endpoints/fan-out are reused as-is.

## 3. Decisions (fixed)

- **Notification richness:** generic **"New mail"**; tap opens the app (WebView →
  inbox). No native mail-content fetch.
- **Push server:** self-hosted **ntfy** at `https://ntfy.whitewolf.tech`.
- **Distributor:** **standard** UnifiedPush distributor — the **ntfy app**,
  installed **via Obtainium** (from `binwiederhier/ntfy` releases), with its default
  server set to `ntfy.whitewolf.tech`. Any UnifiedPush distributor works, but the
  ntfy app is the match for a self-hosted ntfy server.
- **Push payload:** data-light `{"type":"new_mail"}` — email content never traverses
  the push path.

## 4. Architecture & components

### Infra (A)
- **ntfy server** at `ntfy.whitewolf.tech`: install ntfy, run under systemd, front
  with Caddy (TLS), add a DNS A record. ntfy is a UnifiedPush provider out of the
  box; no ntfy-side account/auth is required for v1 (endpoints are random,
  content-free — see §6). The `maileroo-mail` backend POSTs wake-ups to the
  per-device endpoint URLs the app registers.
- **Backend config:** `PUSH_ENDPOINT_HOSTS=ntfy.whitewolf.tech` in
  `/opt/maileroo/maileroo.env`; redeploy. No code change.

### Android (B) — units with clear boundaries
- **`PushApiClient`** — authenticated calls to the backend: `register(endpoint)` →
  `POST /api/push/register` and `unregister(endpoint)` → `POST /api/push/unregister`,
  each with `Authorization: Bearer <token>` from `TokenStore`. Pure-ish
  (OkHttp + token provider); JVM-testable with `MockWebServer`. Returns a simple
  success/failure result; never throws to callers.
- **`PushReceiver`** (extends the UnifiedPush connector's `MessagingReceiver`) —
  - `onNewEndpoint(endpoint, instance)` → `PushApiClient.register(endpoint)`;
  - `onMessage(message, instance)` → post the **"New mail"** notification;
  - `onUnregistered(instance)` → `PushApiClient.unregister(lastEndpoint)`;
  - `onRegistrationFailed(...)` → log.
- **`PushManager`** — orchestrates lifecycle: `enable()` picks a distributor and
  calls `UnifiedPush.register(...)`; `disable()` calls `UnifiedPush.unregister(...)`.
  Exposes `hasDistributor(): Boolean`. Called on app start (when logged-in) and
  after login; `disable()` + backend unregister on sign-out.
- **`Notifications`** — creates the "Mail" `NotificationChannel` and posts the
  generic notification with a tap `PendingIntent` that opens `MainActivity`.
- **`POST_NOTIFICATIONS`** runtime permission request (Android 13+), asked once when
  push is enabled.
- **No-distributor UX:** if `hasDistributor()` is false, show a dismissible hint in
  the shell ("Install the ntfy app via Obtainium and set its server to
  ntfy.whitewolf.tech to get notifications"). Push simply stays off until then; the
  app is fully usable without it.
- **Manifest:** `POST_NOTIFICATIONS` permission; register `PushReceiver` for the
  UnifiedPush broadcast actions.

## 5. Data flow

New inbound mail → backend inbound webhook stores it → backend POSTs
`{"type":"new_mail"}` to the user's ntfy endpoint(s) → ntfy → the distributor (ntfy
app) wakes `PushReceiver.onMessage` → post "New mail" notification → user taps →
`MainActivity` (WebView → inbox, authenticated via the seeded session cookie).

Registration flow: app enabled + distributor present → `UnifiedPush.register` →
`onNewEndpoint` → `PushApiClient.register(endpoint)` (Bearer) → backend stores
`(user_id, endpoint)`.

## 6. Security & privacy

- The push payload is content-free (`{"type":"new_mail"}`); the ntfy endpoint URL is
  a random, unguessable token. Worst case if an endpoint URL leaks: an attacker could
  send spurious "New mail" wake-ups (notification spam) — **no data disclosure**. v1
  accepts this (standard UnifiedPush posture); ntfy access-control can be added later
  if spam becomes a concern.
- The backend only POSTs to hosts in `PUSH_ENDPOINT_HOSTS` (fail-closed, host-pinned)
  and via the SSRF-safe client — an attacker-chosen endpoint host is rejected at
  registration.
- Registration is authenticated (Bearer token → identifies the user); endpoints are
  user-scoped in `push_endpoints`.

## 7. Error handling

- **No distributor installed** → hint shown, no crash; push off until installed.
- **`register`/`unregister` HTTP failure** → logged; re-registration is retried on the
  next app start (the endpoint is re-sent), so it self-heals.
- **Token expired** (register → 401) → skip; re-registers after the next login.
- **Notification permission denied** → wake-ups still arrive but no notification is
  shown; the app keeps working. Re-prompt path documented, not forced.
- **ntfy unreachable / endpoint dead** → backend fan-out already logs and prunes
  endpoints returning 404/410 (PR #24).

## 8. Permissions after this sub-project

`INTERNET`, `POST_NOTIFICATIONS`. No foreground service (that would only arrive with
the deferred embedded distributor).

## 9. Testing

- **JVM unit:** `PushApiClient` register/unregister against `MockWebServer` (Bearer
  header sent; success on 200; failure mapped, not thrown; correct paths/bodies);
  any pure endpoint/payload handling.
- **Instrumented (written, deferred — no emulator):** `PushReceiver.onMessage` posts
  a notification; `onNewEndpoint` calls register; `PushManager.hasDistributor`.
- **Manual/e2e (operator):** install ntfy app via Obtainium → set server →
  install/enable WWT app → send a test mail → confirm the "New mail" notification →
  tap → inbox.

## 10. Onboarding runbook (documented in the repo)

1. Install the **ntfy app via Obtainium** (source: `binwiederhier/ntfy` GitHub
   releases).
2. In the ntfy app, set the **default server** to `https://ntfy.whitewolf.tech`.
3. Install/update the **WWT app** (Obtainium), open it, grant the notification
   permission when asked.
4. Send yourself a test email → a **"New mail"** notification should appear.

## 11. Build sequencing (rough)

(1) Infra: deploy ntfy at `ntfy.whitewolf.tech` + set `PUSH_ENDPOINT_HOSTS` + redeploy
→ (2) `PushApiClient` (JVM-tested) → (3) `Notifications` + channel + `POST_NOTIFICATIONS`
→ (4) `PushReceiver` + manifest registration → (5) `PushManager` lifecycle + shell
wiring (enable on login/start, disable on sign-out, no-distributor hint) →
(6) onboarding doc.
