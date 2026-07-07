# Offline-Aware Error Handling — Design

**Date:** 2026-07-07
**Status:** Approved (user: "do the offline handling fix first")
**Motivation:** Any transient main-frame load failure (Wi-Fi↔cellular handoff, DNS blip, cold start in a dead spot) latches the generic "Couldn't reach Mail." screen until the user taps Retry — this bit the user in production on 2026-07-07 while the server was verifiably healthy the whole time. The shell cannot tell "you're offline" from "the server is down" and never retries on its own.

## Goals

- The error screen distinguishes **offline** ("You're offline. Waiting for a connection…") from **server unreachable** ("Couldn't reach Mail.").
- **Auto-retry** the page load the moment a validated network (re)appears while the error screen is showing.
- While the error screen shows AND the device is online, retry gently every 30 s (recovers from short server blips without user action; mirrors the existing 30 s push-status poll idiom).
- Manual Retry and Sign out remain available in both states.

## Non-goals

- No auth-state distinction (an expired session renders the SPA's login page, not a shell error).
- No offline content/caching.
- No changes to the load-success path, push, or the new back/pull-to-refresh behavior.

## Design

- **`net/ConnectivityMonitor.kt`** — small class wrapping `ConnectivityManager`: `start()` registers a default-network callback and returns a `StateFlow<Boolean>` ("online" = a network with `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED` is available; initial value from a synchronous capabilities check of the current active network); `stop()` unregisters. Registered from `ShellScreen` via `DisposableEffect` only while the shell is composed.
- **Manifest**: add `android.permission.ACCESS_NETWORK_STATE` (normal permission, no prompt).
- **`ShellScreen.kt`** — the `errored` state stays; the error branch becomes offline-aware:
  - `online == false`: text "You're offline. Waiting for a connection…" (plus the Retry/Sign out buttons, unchanged tags).
  - `online == true`: existing "Couldn't reach ${subApp.title}." + buttons.
  - Retry action is unchanged (`errored = false; loading = true; reloadKey++`).
  - **Auto-retry effects** (active only while `errored`):
    - On `online` transitioning false→true: retry immediately.
    - While `online` stays true: retry every 30 s (a `LaunchedEffect(errored, online)` loop with `delay(30_000)`, RESUMED-gated like the push poll).
- **Testable decision logic** — pure function in the ui package (same style as `pushBannerContent`): `errorMessageFor(online: Boolean, title: String): String` returning the exact copy above; JUnit-tested. The retry-policy wiring stays thin Compose effects per repo convention.

## Error handling / edge cases

- A retry that fails while offline just re-latches `errored`; the offline text shows and the next connectivity event tries again — no tight loop (retry only via the 30 s tick or a transition).
- Devices reporting a captive-portal network: `VALIDATED` stays false → treated as offline (correct; the load would fail anyway).
- If the callback registration throws (restricted profiles), fall back to `online = true` permanently — behavior degrades to today's (generic message + 30 s retry), never worse.

## Testing

- JUnit: `errorMessageFor` copy for both states; `ConnectivityMonitor`'s capability-decision helper (`isOnline(hasInternet, hasValidated)` style pure function) if extracted.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` green (CI release gate).
- On-device manual steps documented in the plan (airplane-mode toggle drill).

## Release

Ships with the next `v*.*.*` tag together with the back-button/pull-to-refresh work (tag planned immediately after this merges).
