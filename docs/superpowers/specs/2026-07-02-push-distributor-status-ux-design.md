# Push-Distributor Status UX — Design Spec

**Date:** 2026-07-02
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / push polish (improves the push-notifications sub-app UX)

## 1. Context & goal

The WWT app already detects whether a UnifiedPush distributor is installed and, if
not, shows a hint to install the ntfy app via Obtainium (shipped in the
push-notifications sub-project). Three caveats remain:

1. **Presence, not configuration.** `PushManager.hasDistributor()` only checks that a
   distributor app exists. If ntfy is installed but its default server is not
   `ntfy.whitewolf.tech`, the app registers, hides the hint, and wake-ups never
   route — with no feedback.
2. **Plain overlay, not a banner.** The hint is a transparent `Text` overlaid at the
   top of the WebView; it floats over content and reads as unfinished.
3. **Checked once, not live.** The hint is evaluated once per logged-in entry. If you
   install ntfy while the app is open, nothing updates until the next launch.

**Goal:** make the push-status UX honest and live — detect a mis-pointed distributor,
present status as a proper banner above the WebView, and re-check on resume.

## 2. Scope

**In scope (native, wwt-mobile only):**
- Detect "installed but wrong server" by inspecting the endpoint URL's host against a
  pinned expected host (`BuildConfig.NTFY_HOST`).
- A process-scoped `PushStatusBus` (`StateFlow<PushStatus>`), owned by `WwtApp`
  alongside `WakeBus`, published from `PushReceiver.onNewEndpoint` and `ShellScreen`.
- A proper Material banner in the shell, laid out above the WebView (not overlaying),
  with a per-state message and a **Dismiss** button.
- Re-check distributor state on `ON_RESUME` (liveness).

**Out of scope (deferred / not needed):**
- **Deep-link / action buttons** (open ntfy, open Obtainium) — guidance text +
  Dismiss only; no intent handling or package assumptions.
- **"Send test notification"** — no new backend test endpoint; no cross-repo change.
- **Backend / web changes** — none. Backend host-pinning (`PUSH_ENDPOINT_HOSTS`) is
  unchanged and remains the server-side guard.
- **Persisted dismissal across launches** — dismissal is per-session (see §5).

## 3. Decisions (fixed)

- **Banner = guidance text + Dismiss.** No deep-links, no test action.
- **"Wrong server" is decided client-side from the endpoint host** — the endpoint URL
  the distributor issues *is* its configured server; comparing its host to
  `BuildConfig.NTFY_HOST` is precise and needs no network. The backend rejecting a
  non-pinned host is the redundant server-side guard, not the UX signal.
- **Expected host is pinned in `BuildConfig`** (`NTFY_HOST = "ntfy.whitewolf.tech"`),
  parallel to `MAIL_BASE_URL` — no host string buried in logic.
- **Three states only:** `Ok` (no banner), `NoDistributor`, `WrongServer(host)`.
  Transient backend-register failures on a correct host are **not** surfaced (avoids
  nagging; self-heals on the next register).
- **Unparseable endpoint → `Ok`** (never a false alarm).

## 4. Architecture & components

### New / changed (all in wwt-mobile, package `tech.whitewolf.app.push` unless noted)

- **`PushStatus`** — sealed type:
  - `object Ok`
  - `object NoDistributor`
  - `data class WrongServer(val endpointHost: String)`
  Data-light; no Android types → JVM-testable.
- **`pushStatusForEndpoint(endpoint: String, expectedHost: String): PushStatus`** —
  pure function. Parses the endpoint URL host; equal (case-insensitive) to
  `expectedHost` → `Ok`; a different, parseable host → `WrongServer(host)`; an
  unparseable URL → `Ok`. JVM-testable.
- **`PushStatusBus`** — process-scoped holder: `val status: StateFlow<PushStatus>`
  (initial `Ok` → no banner until a problem is known) and `fun set(s: PushStatus)`.
  Owned by `WwtApp` next to `WakeBus`.
- **`BuildConfig.NTFY_HOST`** — new `buildConfigField("String", "NTFY_HOST",
  "\"ntfy.whitewolf.tech\"")` in both build types in `app/build.gradle.kts`.
- **`PushReceiver.onNewEndpoint`** (modify) — after the existing save + backend
  register, publish `pushStatusForEndpoint(endpoint, BuildConfig.NTFY_HOST)` to the
  bus via `WwtApp.from(app)`.
- **`ShellScreen`** (modify) — replace the overlay `Text` with the banner; drive
  status: on enter, `!hasDistributor()` → publish `NoDistributor`, else `enable()`;
  collect `PushStatusBus.status`; on `ON_RESUME`, re-check `hasDistributor()` and
  re-`enable()`/republish as needed.
- **Banner composable** — a Material3 `Surface` at the top of a `Column` that also
  holds the WebView (`Modifier.weight(1f)`), so the banner occupies its own space
  above the WebView. Shows the per-state message + a **Dismiss** `TextButton`.

`PushManager` (`hasDistributor` / `enable` / `disable`) is unchanged. `WakeBus` and
the wake-to-sync path are untouched.

## 5. State machine & data flow

**States → banner:**
- `Ok` → no banner.
- `NoDistributor` → "Notifications are off. Install the **ntfy** app via Obtainium and
  set its server to `ntfy.whitewolf.tech`."
- `WrongServer(host)` → "ntfy is installed but pointed at **{host}**. Open ntfy and set
  its server to `ntfy.whitewolf.tech` for notifications."

**Flow:**
- **Login / app enter:** `ShellScreen` → `hasDistributor()` false → `set(NoDistributor)`;
  true → `enable()` (→ `registerApp` → `onNewEndpoint`).
- **`onNewEndpoint`:** save + backend-register (unchanged), then
  `set(pushStatusForEndpoint(endpoint, NTFY_HOST))` → `Ok` or `WrongServer`.
- **Resume (`ON_RESUME`):** re-evaluate `hasDistributor()`. Newly present (ntfy
  installed while open) → `enable()` (status resolves via `onNewEndpoint`) and leave
  `NoDistributor` behind; newly absent → `set(NoDistributor)`.

**Dismissal:** Dismiss records the current status in a remembered `dismissedStatus`;
the banner is shown only when `status` is a problem **and** `status != dismissedStatus`.
A change to a *different* problem re-shows; `Ok` clears it. Not persisted across
launches — a cold start re-shows an unresolved problem once (gentle, not naggy).

## 6. Error handling

- **Unparseable endpoint URL** → `Ok` (no false alarm). A genuinely broken endpoint is
  still caught by the backend register + existing log.
- **Distributor present, no endpoint yet** (registration in flight) → status stays at
  its prior value (initially `Ok`) → no banner flicker; resolves when `onNewEndpoint`
  fires.
- **Backend `register()` fails on a correct host** (transient network / expired token)
  → not surfaced as a banner; self-heals on the next launch/resume re-register (as
  today). Only host mismatch drives `WrongServer`.
- **Host compare is case-insensitive** and ignores port (compare host only).

## 7. Security & privacy

- No new permissions, no new network calls, no `@JavascriptInterface`. The endpoint
  host is data the app already receives via `onNewEndpoint`. `WrongServer` carries only
  a hostname (no token/path) for display. Backend host-pinning is unchanged.

## 8. Testing

- **JVM unit:** `pushStatusForEndpoint` — `https://ntfy.whitewolf.tech/UPxxxx` → `Ok`;
  `https://ntfy.sh/UPxxxx` → `WrongServer("ntfy.sh")`; mixed-case host → `Ok`;
  `"not a url"` → `Ok`. `PushStatusBus` set/get round-trip.
- **Instrumented (written, deferred — no emulator):** `NoDistributor` when none
  installed; correct banner text per state; `ON_RESUME` re-check flips
  `NoDistributor` → `Ok` after a distributor appears.
- **Manual/e2e (operator):** (a) no ntfy → no-distributor banner; (b) ntfy on default
  `ntfy.sh` → wrong-server banner naming `ntfy.sh`; (c) fix server in ntfy → banner
  clears on resume; (d) all correct → no banner; (e) push still delivers.

## 9. Build sequencing (rough)

(1) `PushStatus` + `pushStatusForEndpoint` (JVM-tested) → (2) `PushStatusBus` +
`BuildConfig.NTFY_HOST` + wire into `WwtApp` → (3) publish from
`PushReceiver.onNewEndpoint` → (4) `ShellScreen` banner + status drive + `ON_RESUME`
re-check → (5) manual e2e. One PR (wwt-mobile).

## 10. Follow-ups (logged, not in this sub-project)

- **Distributor-installed deep action** (open ntfy to fix the server, or open Obtainium
  to install) — deferred with the rest of the actionable-banner ideas.
- **Banner is still shell-local**; if a future sub-app needs the same status surface,
  promote it to a shared component then.
