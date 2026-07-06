# Back Button + Pull-to-Refresh — Design

**Date:** 2026-07-06
**Status:** Approved (user-selected sub-project; pull-to-refresh approach follows the recommended native-gesture-with-SPA-bridge option)
**Motivation:** Two high-feel shell gaps: the system back button exits the app instead of navigating mail (the SPA now creates real history entries — webmail PR #32), and there is no pull-to-refresh. The webmail side of the refresh contract is already live (webmail PR #33): the SPA calls `window.WwtShell.setAtTop(boolean)` when hosted by the shell.

## Goals

- System back navigates the WebView's history (thread → list, close compose); only at the history root does it fall through to the default behavior (leave the app).
- Pull-to-refresh: a native swipe-down gesture at the top of the mail list triggers a mailbox refresh (`window.wwtWake()`), with the standard Android spinner.
- The gesture must never hijack in-page scrolling: it arms only when the SPA reports the list is visible and scrolled to top.

## Non-goals

- No refresh completion signal from the SPA (none exists); the spinner runs a fixed short interval.
- No changes to push, auth, error handling, or notifications.

## Design

### 1. Back button (`SubAppWebView.kt`)

- Track `canGoBack` as Compose state, updated in a `doUpdateVisitedHistory` override (fires for both full loads and the SPA's `pushState`/hash entries).
- `BackHandler(enabled = canGoBack) { webView?.goBack() }` — when disabled (history root), Android's default back disposes the activity as before. No JS bridge needed; the SPA's base-entry seeding guarantees in-app deep links keep a list entry beneath them.

### 2. Pull-to-refresh (`SubAppWebView.kt` + new `web/ShellBridge.kt`)

- New dependency: `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` (view-based; the Compose pull-refresh containers cannot see a WebView's inner scroll, SwipeRefreshLayout + an explicit child-scroll callback can be gated precisely).
- `ShellBridge` (package `tech.whitewolf.app.web`): tiny class holding `@Volatile var atTop: Boolean = false` with `@JavascriptInterface fun setAtTop(v: Boolean)`. Unit-tested plain Kotlin.
- The WebView gains `addJavascriptInterface(bridge, "WwtShell")`. The factory wraps the WebView in a `SwipeRefreshLayout`:
  - `setOnChildScrollUpCallback { _, _ -> !bridge.atTop }` — "child can still scroll up" whenever the SPA hasn't reported at-top, so the gesture arms ONLY at the top of the visible list (and never over compose/thread/settings).
  - `setOnRefreshListener`: if the page has loaded, evaluate `window.wwtWake && window.wwtWake()` and stop the spinner after 800 ms (no completion signal exists; the SPA's fetch is fast); if the page never loaded, `reload()` instead and stop the spinner on the next `onPageFinished`.
- Fail-safe defaults: `atTop` starts `false`, so against an older deployed SPA (no bridge calls) the gesture simply never arms.

### 3. Security note

`addJavascriptInterface` is exposed to pages in this WebView. Main-frame navigation is already pinned to the sub-app host by `NavPolicy`; mail-content iframes are sandboxed without scripts by the webmail renderer. The interface accepts a single boolean and controls only whether a refresh gesture arms — worst case misuse is an inert or over-eager spinner, no data exposure.

## Testing

- JUnit unit tests (the repo's CI gate runs `:app:testDebugUnitTest`): `ShellBridge` set/read across threads (volatile visibility is declarative; test the setter/getter contract), plus the refresh-decision helper if extracted (`wakeOrReload(pageLoaded)` style) — keep UI wiring thin per repo convention.
- Manual verification steps documented in the plan (requires a device/emulator; not available in CI or this environment): back-navigates thread→list→exit; pull at top of list spins and refreshes; pull mid-list scrolls instead.

## Release

Merging does not ship anything: release builds go out via a `v*.*.*` tag (Obtainium pipeline). Tagging is left to the operator.
