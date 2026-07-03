# Notification-Permission Check in the Push Wizard — Design Spec

**Date:** 2026-07-03
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / push polish (extends the v0.3.4 step-wizard banner)

## 1. Context & goal

The push-setup wizard covers the transport (step 1: install ntfy, step 2: point it
at `ntfy.whitewolf.tech`) but not the WWT app's own `POST_NOTIFICATIONS` state. If
the user denied the first-launch prompt, later disabled notifications in system
settings, or blocked the "Mail" channel, `Notifications.showNewMail()` no-ops
silently — pushes arrive, nothing shows, and the wizard says nothing.

**Goal:** the wizard also verifies the WWT app's notification permission and, when
transport is healthy but notifications are blocked, shows a final guidance step
that clears itself once fixed.

## 2. Scope

**In scope (wwt-mobile only):**
- Detect "notifications blocked for WWT": app-level (`areNotificationsEnabled()`)
  and Mail-channel-level (importance `NONE`).
- A final, unnumbered wizard step shown only when transport is `Ok` and
  notifications are blocked.
- Live re-evaluation on entry, `ON_RESUME`, and the existing 30s poll (whose
  problem gate extends to include the permission state).

**Out of scope (unchanged / rejected):**
- **ntfy's own permission** — rejected: package-specific and it does not gate
  delivery to WWT (only ntfy's own status notifications).
- **Deep-link to system settings** — guidance text only, consistent with the
  banner's no-intents scope (still the actionable-banner follow-up).
- **Why-blocked diagnosis** (denied prompt vs later disable) — indistinguishable
  and irrelevant: the fix is the same settings screen.
- `PushStatus`, `PushStatusBus`, `PushReceiver`, the first-launch permission
  request in `MainActivity` — all untouched.

## 3. Decisions (fixed)

- **Detection:** `notificationsEnabled =
  NotificationManagerCompat.areNotificationsEnabled(context) && (the
  Notifications.CHANNEL_ID channel's importance != IMPORTANCE_NONE)`. A missing
  channel counts as enabled (it is created on first notification).
- **The permission state lives OUTSIDE `PushStatusBus`** — as a
  `notificationsEnabled` boolean in `ShellScreen`, refreshed inside `recheck()`.
  Rationale: `PushReceiver.onNewEndpoint` overwrites the bus with `Ok`/
  `WrongServer` whenever an endpoint arrives; a permission state stored there
  would be clobbered.
- **Priority: transport first.** Steps 1–2 render exactly as in v0.3.4 whenever
  the distributor state is a problem, regardless of the permission flag. The
  permission step shows only for `Ok` + blocked.
- **Exact content of the permission step:** stepLabel
  `"Notifications — one last thing"`, instruction
  `"Allow notifications for WWT in Settings → Apps → WWT → Notifications."`,
  no copy row.
- **`BannerContent.copyUrl` becomes `String?`** — the URL+Copy row renders only
  when non-null. Steps 1–2 keep their URLs; the permission step has none.
- **Numbering unchanged** for transport ("step 1 of 2" / "step 2 of 2"): most
  users granted the permission at first launch, so a fixed "of 3" would overstate
  the common journey. The permission step is deliberately unnumbered.

## 4. Components & changes

All in `app/src/main/java/tech/whitewolf/app/ui/`.

- **`PushStatusBanner.kt`** (modify):
  - `BannerContent(val stepLabel: String, val instruction: String,
    val copyUrl: String?)` (copyUrl now nullable).
  - `fun pushBannerContent(status: PushStatus, notificationsEnabled: Boolean):
    BannerContent?` — `when (status)`: problems → the existing step-1/step-2
    content (unchanged strings/URLs); `Ok` → if `notificationsEnabled` null else
    the permission step (§3).
  - Composable: the URL `Row` renders only when `content.copyUrl != null`;
    everything else unchanged (styles, testTags, clipboard, toast gate).
- **`ShellScreen.kt`** (modify):
  - `var notificationsEnabled by remember { mutableStateOf(true) }` (start true →
    no flicker before the first check).
  - A small helper (top-level in `ShellScreen.kt` or local) computes §3's
    detection from a `Context`; `recheck()` sets both the distributor-driven
    status (as today) and `notificationsEnabled`.
  - Poll gate: `isProblem = pushStatus !is PushStatus.Ok || !notificationsEnabled`.
  - Banner call: `pushBannerContent(pushStatus, notificationsEnabled)`.

No new permissions, dependencies, or network calls. `NotificationManagerCompat`
is already on the classpath (used by `Notifications.kt`).

## 5. Error handling

- Channel lookup happens via `NotificationManagerCompat.getNotificationChannel`
  (API-safe on minSdk 29); missing channel → enabled (per §3).
- All checks are synchronous local calls inside `recheck()` — no new failure
  modes; a wrong transient value self-corrects on the next re-check (≤30s).

## 6. Testing

- **JVM unit** (extend `PushStatusBannerTest`) — `pushBannerContent` matrix:
  - `NoDistributor` / `WrongServer` with `notificationsEnabled = false` → the
    transport steps (unchanged content; transport wins).
  - `Ok` + `false` → permission step: exact label/instruction, `copyUrl == null`.
  - `Ok` + `true` → `null`.
  - Existing step-1/step-2 exact-content tests updated only for the added
    parameter.
  (The detection helper touches Android framework classes → covered by the build
  gate + operator, not JVM.)
- **Build gate:** `:app:assembleDebug` + full JVM suite.
- **Manual/e2e (operator):** with transport healthy, disable notifications for
  WWT in system settings → "one last thing" banner on resume; re-enable →
  clears on resume (or ≤30s split-screen). Block only the Mail channel → same.
  Deny-at-first-install path: fresh install, deny the prompt → banner appears
  once transport is set up.

## 7. Build sequencing (rough)

Single code task (`BannerContent` nullability + `pushBannerContent` second
parameter + permission step + `ShellScreen` detection/gate wiring, JVM-tested) →
review → PR → release **v0.3.5** on the operator's word.

## 8. Follow-ups (unchanged)

Actionable banner (deep-link to Obtainium/ntfy/system settings) remains the
logged follow-up — the permission step is its strongest future candidate (a
direct `APP_NOTIFICATION_SETTINGS` intent).
