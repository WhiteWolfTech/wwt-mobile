# Session Invalidation on Server 401 — Design

**Date:** 2026-07-14
**Status:** Shipped (PR #20 + the session-expired notice PR)
**Linear:** [WWT-57](https://linear.app/white-wolf-technology/issue/WWT-57)

**Motivation:** The shell keeps a native bearer token (`TokenStore`) separate from the
WebView session, and `AuthRepository.isLoggedIn()` was a purely local check — the token was
never validated against the server. When the server rejected it with 401, the two stores
diverged: the SPA fell back to its in-page login form so **mail kept working**, but the
native `TokenStore` still held the dead bearer and `PushApiClient` kept sending it.
`PushApiClient.post` returned `it.isSuccessful`, so the 401 was flattened into a bare
`false` and thrown away. Net effect: **push registration silently broke**, with no crash and
no visible error, until the user happened to perform a native sign-out/sign-in. This went
live on 2026-07-13 when the WWT-50 deploy bumped `token_version` and force-invalidated every
existing session; the now-shipped WWT-51 password change bumps it too, so the trigger is
reachable in normal use.

## Goals

- A server 401 on a native API call invalidates the native session: clear `TokenStore` +
  WebView cookies, and surface the native login screen.
- The native and WebView token stores cannot silently diverge — one native login re-seeds both.
- The login screen explains why the user is there ("Your session expired. Please sign in
  again."), so an involuntary logout does not read as a bug.
- A **deliberate** sign-out shows no such notice.

## Non-goals

- No write-back of the WebView/SPA login token into the native `TokenStore` (the alternative
  fix). It needs a coordinated change across two repos and two deploys; the native bounce is
  self-contained and closes the silent-breakage hole. Can be added later as UX polish.
- No change to login, push status, or the offline/error paths.

## Design

- **`push/PushApiClient.kt`** — stop discarding the 401. Gains one collaborator,
  `onUnauthorized: () -> Unit`, invoked **only** on a genuine HTTP 401. `IOException` and
  non-401 failures (5xx, etc.) keep returning `false` **without** firing the callback. Call
  sites keep their `Boolean` contract, so `PushReceiver` and `signOut` are unchanged.

- **`auth/AuthRepository.validate()`** — a proactive `GET /api/me` with the bearer, run on
  shell entry and on resume. A 401 invalidates; anything else (offline, 5xx) leaves the
  session alone.

  *This was originally listed as a non-goal*, on the reasoning that `ShellScreen.recheck`
  re-registers push on every entry and resume, so a dead token is exercised anyway. That
  argument holds **only when a distributor is installed**. With no distributor (`PushStatus.
  NoDistributor`) no push call is ever made, so nothing would ever discover the dead token
  and the stale bearer would live forever. The probe is one cheap GET and closes that hole.

- **`auth/SessionBus.kt`** (new) — process-scoped `StateFlow`s for `loggedIn` and
  `invalidated`, modelled on the existing `PushStatusBus`. It exists because `PushReceiver`
  runs on a background thread inside a broadcast receiver, outside composition; `StateFlow`
  is the established safe hand-off to the shell UI in this codebase. `ShellScreen` reads
  signed-in state from the bus rather than a local `remember` flag — a local flag is what let
  the two stores diverge in the first place.

- **`AppContainer.kt`** — owns the bus (not `WwtApp`: `ShellScreen` already receives
  `container`, so no new plumbing) and wires `PushApiClient`'s 401 to `auth.invalidate()`.

- **`ui/ShellScreen.kt`** — collects `sessionBus.invalidated` and passes the notice to
  `LoginScreen`. `signOut` brackets its teardown in `beginSignOut()` / `endSignOut()`.

- **`ui/LoginScreen.kt`** — gains an optional `notice: String?`, rendered above the form when
  non-null. Copy: `Your session expired. Please sign in again.`

## Error handling / edge cases

- **Only a 401 invalidates.** This is the load-bearing safety property: were `IOException` or
  a 5xx to clear the token, a flaky network or a brief server blip would log users out. The
  original code could not distinguish these — that is exactly the defect.
- **Deliberate sign-out must stay silent.** `ShellScreen.signOut` calls `unregister()` with
  the live bearer *before* `auth.logout()`. If the server has already revoked that token,
  `unregister()` gets a 401 → fires `invalidate()` → the user would see "Your session
  expired" after *choosing* to sign out. `beginSignOut()` gates the flag for the duration of
  the teardown, and `endSignOut()` runs in a `finally` — a gate left raised would suppress
  every real expiry notice from then on, which is worse than the spurious notice it prevents.
- **The gate is atomic, not "clear it afterwards".** `invalidate()` and
  `beginSignOut()`/`endSignOut()` are `@Synchronized`: `signingOut` and `invalidated` are one
  invariant across two fields, so the check-then-set must be mutually exclusive with the
  set-then-clear. `@Volatile` would not do — it makes individual accesses visible, not the
  compound operation atomic. `SessionBusTest` races the two 500 times.
- **Re-entrancy.** `auth.logout()` and `TokenStore.clear()` are idempotent, so a 401 arriving
  on both `register` and `unregister` is harmless.
- **Offline.** No network, no HTTP response, no 401 → nothing fires → the user stays logged
  in. Correct.

## Testing

Unit (`app/src/test`, `mockwebserver` already a test dependency):

- `PushApiClient`: **401** on register *and* unregister → `onUnauthorized` fired, returns
  `false`. **500** → not fired. **200** → not fired. The negative cases are the regression
  guard against logging users out on a blip.
- `AuthRepository.validate()`: bearer sent to `/api/me`; 200 keeps the session; 401 clears
  token + cookies + bus and raises the notice; network failure and 500 keep it; `logout()`
  raises no notice; a fresh login clears a stale one.
- `SessionBus`: transitions, sign-out suppression, and the concurrent invalidate/beginSignOut
  race.
- `sessionNoticeFor()`: the copy.

Gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug` green.

Manual (on device, per the Linear "Verify" note): with a server-revoked token, confirm the
app surfaces the native login *with the notice*, and that after signing in, push registration
succeeds (no repeated 401s on `/api/push/*` in the server log).

## Release

Patch release of the shell; no backend or SPA change required.
