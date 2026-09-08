# Shell Restructure + Suite Identity — Design

**Date:** 2026-09-08
**Status:** Approved, not started
**Linear:** not yet filed
**Project:** A of A–E (see *Scope and decomposition* below)

**Motivation:** `subapp/SubAppRegistry.kt` claims that adding a sub-app is "one new entry
here". That claim is false. The shell is a mail app wearing a shell's clothes: identity,
push and navigation are all bound to the mail backend, and `SubApp` is defined *as* a URL,
so a native sub-app cannot exist at all. Sub-app #2 — a video app whose server sources and
edits videos, notifies the app when one is ready, and lets the user watch it — is **fully
native**. Adding it to the shell as it stands would mean threading mail-shaped assumptions
through every one of the seams below. This project makes the registry's claim true, and
delivers no user-visible feature: its entire justification is making projects B–D cheap.

Seams currently hardwired to mail, verified 2026-09-08:

| Seam | Today |
| --- | --- |
| `subapp/SubApp.kt` | `data class SubApp(id, title, url)` — a sub-app *is* a URL |
| `subapp/SubAppRegistry.kt:13-14` | `all()` returns one entry; `default()` auto-opens it |
| `AppContainer.kt:22-25` | One `baseUrl` from the mail origin, used for auth *and* push |
| `ui/SubAppWebView.kt:165` | Session cookie seeded only at `subApp.url` |
| `web/NavPolicy.kt` | Main frame pinned to a single allowed host |
| `push/Notifications.kt:23,24,51` | One channel `"mail"`, one notification ID `1`, "New mail" |
| `push/PushApiClient.kt` | Registers the UnifiedPush endpoint with the *mail* API |
| `push/WakeBus.kt` | One global tick → whichever WebView is showing; no routing |
| `AndroidManifest.xml` | `LAUNCHER` filter only; no deep links, no `onNewIntent` |
| `ui/ShellScreen.kt` | ~270 lines: session gate, push health, connectivity retry, layout |

## Scope and decomposition

Sub-app #2 in full is six or seven independent subsystems. It is split so the product's
promise — *server finishes a video, you are told, you watch it* — lands as early as possible:

- **A — Shell restructure + suite identity (this spec).** Sub-app model, launcher and
  navigation, per-sub-app identity, push routing. No user-visible feature.
- **B — Video walking skeleton.** Library list, streaming Media3 player, "video ready"
  notification deep-linking to that video.
- **C — Playback depth.** `MediaSessionService`, background playback, PiP, lock-screen controls.
- **D — Offline download.** Media3 `DownloadManager`, storage quota, sign-out retention policy.
- **E — Casting.** Blocked: Google Cast requires Play Services, which this project
  deliberately does not have (`docs/PUSH.md`: "No Google Play Services are used"). Must be
  re-scoped to external display / DLNA or moved server-side before it can be designed.

## Goals

- A sub-app can be web-hosted or fully native, and the shell knows which only through one
  small interface.
- Adding a sub-app is a registry entry plus an implementation — no edits to the shell's
  navigation, identity, or push code.
- One interactive sign-in yields working sessions for every sub-app's backend.
- A wake or notification for one sub-app never refreshes or signs out another.
- Switching sub-apps does not reload the mail SPA.
- `ShellScreen` stops being four concerns in one composable.

## Non-goals

- No video sub-app. `VideoSubApp` is project B; this spec only proves the seams by showing
  where it will plug in.
- No new JS-bridge surface. The foundation spec budgeted share/open-external/biometric and
  they were never built; they stay unbuilt.
- No RP-initiated logout at Authelia. Signing out of the app must not sign the user out of
  the mail SPA in their phone browser.
- No rotation-survival for the WebView. The Activity is recreated on rotation today and the
  SPA reloads; that is the status quo and is not addressed here.
- No deep-link registration for mail. Only the internal `wwt://` scheme used by our own
  notifications; no `https://mail.whitewolf.tech` app-links handling.

## External prerequisites

Both must be confirmed before implementation starts. Neither is in this repo.

1. **Authelia issues long-lived, rotating refresh tokens to the public client
   `maileroo-mobile`.** Four things, not one — the client today has
   `scopes: [openid, profile, email, groups]` and `docs/AUTH.md` states plainly "There is no
   refresh endpoint", so all four are new:
   - `offline_access` added to the app's scope (`auth/OidcAuthService.kt:45`) **and**
     `refresh_token` added to the client's `grant_types`.
   - **A refresh-token lifespan longer than the backend session it renews.** Authelia's
     fosite defaults are on the order of 90 minutes, which would be shorter than mail's
     7-day session and make refresh useless. This must be set per-client to at least the
     longest backend session lifetime, and realistically 30–90 days — otherwise the launch
     keep-alive below signs the user out on any launch after a short absence, which is far
     worse than today's behaviour.
   - **Rotation-on-use must be handled, not assumed away.** If refresh tokens rotate, the
     replacement must be persisted *before* it is used, or a crash between the token
     response and `SecureStore.putString` orphans the chain and the next refresh fails
     `invalid_grant` — an unrecoverable sign-out from a single badly-timed kill.
   - **The refresh-issued ID token must still carry `preferred_username` and `groups`.**
     `internal/oidc/oidc.go` rejects a token without `preferred_username`, so a claims
     policy that only populates it on the interactive authorization would break every
     re-mint while looking like a backend bug.

   If the lifespan or rotation requirements cannot be met, the fallback is to keep today's
   behaviour — no refresh, and a backend 401 bounces to interactive sign-in — which costs
   the "expiry is invisible" property but leaves the rest of this design intact.
2. **The video backend exposes a `/api/auth/native`-shaped endpoint.** It verifies an
   Authelia ID token and returns `{ok, token, expires}`. `email-client-maileroo`'s
   `internal/oidc/native.go` already does exactly this, including the `aud == client_id`
   check, so this is a port rather than a design. The suite continues to use one client id;
   renaming `maileroo-mobile` to something suite-shaped is optional tidying that must be
   coordinated across Authelia and both backends, and is not required by this spec.

## Design

### 1. Sub-app model and registry

`SubApp` becomes an interface whose implementations own their dependencies:

```kotlin
@JvmInline value class SubAppId(val value: String)   // "mail", "video"

interface SubApp {
    val id: SubAppId
    val title: String
    val icon: ImageVector
    @Composable fun Content(host: SubAppHost, modifier: Modifier)
}

/** What the shell can say to a sub-app while it is on screen. */
interface SubAppHost {
    val deepLink: StateFlow<Uri?>   // "open video X"; consumed by the sub-app
    val wake: Flow<Unit>            // "something changed, refetch"
    fun onDeepLinkHandled()
}
```

The interface is UI-shaped: no `baseUrl`, no token, no HTTP client. A sub-app is
*constructed* with what it needs (`MailSubApp(mailSession, cookies)`), so the shell never
learns that mail has an origin or that video has a player. This is the property to defend in
review; every future request to add a field here should first be tried as a constructor
argument.

`SubAppHost` exists because `Content(modifier)` alone gives the shell no way to deliver a
deep link, a wake, or a teardown. Defining the interface in A and widening it in B is the
worst possible order, so the three inbound events are settled now. Three events is not a
god-interface; a fourth should be argued for.

`SubAppId` is a value type because that string becomes five things: the launcher's
last-used preference key, the notification channel id, the deep-link path segment, the wake
routing key, and the UnifiedPush instance name. One definition, not five literals.

**Push is a second facet, not more surface on `SubApp`.** `PushReceiver` is a
`BroadcastReceiver` running on a background thread with no Activity; it cannot touch a
`@Composable`. One registry entry therefore carries two facets:

```kotlin
/** What arrived on the wire. Deliberately thin: an optional item id and nothing else. */
data class WakePayload(val subAppId: SubAppId, val itemId: String?)

interface SubAppPush {
    val channelId: String        // SubAppId.value
    val channelName: String
    fun notify(context: Context, payload: WakePayload)   // owns its notification IDs
    fun tapIntent(payload: WakePayload): Uri             // wwt://subapp/<id>/<item>
}

data class SubAppEntry(val ui: SubApp, val push: SubAppPush?)
```

`SubAppRegistry` becomes an instance built by `AppContainer` (it currently reads
`BuildConfig` as a static `object`, which cannot construct sub-apps with dependencies). It
must remain UI-free and safe to build off the main thread, because `PushReceiver` already
constructs `AppContainer` on a background thread (`push/PushReceiver.kt:18-21`). It exposes
`all()` and `byId()`; `default()` is deleted — "what opens on cold start" moves to the
launcher's last-used state.

**Mail migrates rather than changes.** `MailSubApp` wraps the existing WebView host. The
WebView machinery (cookie seeding, allowed host, `NavPolicy`, pull-to-refresh, `wwtWake`,
the load-error UI) is extracted from `ShellScreen` and `SubAppWebView` into a composable
that stays **mail-shaped**. It is not parameterised into a generic `WebSubAppContent` for a
hypothetical sub-app #3; generalising one implementation for one caller is speculation, and
the extraction can be generalised when a second web sub-app actually exists.

`@Composable` in an interface is unusual Kotlin, but it is the honest expression of "a
sub-app is a screen". A `SubAppRenderer` indirection was considered and rejected as buying
nothing.

### 2. Navigation, back behavior, and content retention

**Navigation is hand-rolled.** `ShellRoute` is a sealed type (`Launcher` | `Open(SubAppId)`)
held in a `ShellViewModel` backed by a `SavedStateHandle`, so the route survives process
death and rotation. `navigation-compose` is not used: the destinations are flat, there are
no nested stacks, and the dependency does not fit a codebase whose DI is deliberately
manual. The costs this choice takes on are named rather than discovered — route
persistence, `onNewIntent`, and predictive-back integration are all handled explicitly
below.

The launcher lists `registry.all()` as tiles. Cold start opens the last-used sub-app, so
mail remains zero-tap for a mail-only user; the last-used `SubAppId` persists in a plain
`SharedPreferences` (it is not a secret, and `EncryptedSharedPreferences` is deprecated
upstream — no reason to widen that dependency for one string).

**The launcher always sits beneath a sub-app**, including on cold-start-last-used and on a
notification deep link. A mail-only user therefore presses back twice to leave from their
inbox. This is a deliberate trade of one keypress for one consistent model, and it makes
the deep-link case require no special rule.

| Where | Back does |
| --- | --- |
| Login screen | Exit app (default) |
| Launcher | Exit app (default) |
| Sub-app, WebView has history | Walk WebView history |
| Sub-app, native internal nav (list → player) | Sub-app handles it internally |
| Sub-app, at content root | Return to launcher |
| Sub-app, showing its load-error screen | Return to launcher |
| Deep-linked into a sub-app (cold or warm) | Return to launcher |

**Ordering rule:** the shell's `BackHandler` (Open → Launcher) must be composed **before**
`Content()`. Effects register in composition order and `OnBackPressedDispatcher` dispatches
LIFO, so a sub-app's own handler — the WebView history walk today, a native list→player pop
tomorrow — must be registered later to win. There is no shell-level handler today, so this
is a new invariant rather than an existing one being written down.

One consequence for the error row above: `BackHandler(enabled = canGoBack)`
(`ui/SubAppWebView.kt:59`) currently arms whenever history exists, and with retention
`canGoBack` can be true *while the load-error screen is showing* — a main-frame error on a
later navigation leaves earlier history intact. Back would then walk WebView history behind
an error screen instead of returning to the launcher. Mail's composable must disable its own
handler while `errored` is set.

Predictive back is opt-in at `targetSdk 35` and the app does not currently set
`enableOnBackInvokedCallback`. Compose's `BackHandler` is built on `OnBackPressedCallback`
and keeps working either way. The relevant hazard is that the system chooses its animation
at gesture *start* from each callback's `enabled` flag, which makes the state-priming rule
below a correctness requirement rather than polish.

**Content retention.** Navigating to the launcher and back re-runs `AndroidView`'s factory,
which builds a new WebView and reloads the SPA: the shell reloads `subApp.url` — the root,
not the current URL — so the user loses their hash route, scroll position, attachments, and
pays a refetch. Compose text is *not* lost: the SPA autosaves it to `localStorage` per user
(`email-client-maileroo/web/src/draft.ts`). This is a meaningful UX regression, not a
correctness bug, and it is sized accordingly.

Retaining a bare `WebView` does not work. Every behavior is wired through closures created
in the factory over composable-local state — `onPageFinished` writes `pageLoaded` and clears
the `SwipeRefreshLayout` (`ui/SubAppWebView.kt:143-147`), `doUpdateVisitedHistory` writes
`canGoBack` (`:154`), `shouldOverrideUrlLoading` captures the Activity (`:129`). Re-attaching
a retained WebView into a fresh composition leaves the retained client writing to dead
state: `canGoBack` resets so back leaves the sub-app mid-history, and pull-to-refresh sees
`pageLoaded == false` and calls `reload()` — the exact reload retention exists to prevent.

So retain a **session object**, not a view:

```kotlin
class MailWebSession {
    val webView: WebView
    val refreshLayout: SwipeRefreshLayout
    val pageLoaded: MutableState<Boolean>
    val canGoBack: MutableState<Boolean>
    val errored: MutableState<Boolean>
    var listener: SessionListener?    // page-finished / history / error callbacks,
}                                     // rebound by each composition
```

The composable binds and unbinds `listener` in a `DisposableEffect`, primes
`canGoBack` from `webView.canGoBack()` on attach, and calls `webView.onPause()` on detach
and `onResume()` on attach so JS timers stop while the launcher is up.

**The retained scope belongs to the sub-app, not the shell.** A shell-level
`RetainedWebViews` keyed by `SubAppId` would contradict section 1 — the shell would be
learning that sub-apps have WebViews. Instead the shell hands each sub-app a retained scope;
`MailSubApp` stores its `MailWebSession` there, and `VideoSubApp` will store its list state
and `MediaController` binding in the same place with no shell change.

Scope and lifetime:

- Held by `remember` at the `ShellScreen` root with the real Activity context. Not a
  `ViewModel`, and no `MutableContextWrapper`: rotation already recreates the Activity and
  reloads the SPA today, so the only new requirement is surviving the launcher round-trip
  *within* one Activity, which `remember` satisfies with no leak analysis.
- **Discarded on sign-out**, driven by `SessionBus`. This is a security requirement, not
  hygiene: sign-out currently works by accident of composition (`ui/ShellScreen.kt:55-67`
  returns early, the WebView leaves composition), and `AuthRepository.forget()` clears only
  the token and cookies. A scope that outlived sign-out would re-attach the previous user's
  live DOM, in-memory inbox and `localStorage` draft under a different session cookie.
- Exposes `discard(id)` so the existing retry path (`key(reloadKey)`, `ui/ShellScreen.kt:233`)
  still forces a genuinely fresh WebView.
- **Destroys explicitly.** `remember` has no disposal hook, so `WebView.destroy()` must be
  driven from a `RememberObserver` or a root `DisposableEffect` on `discard`, sign-out and
  Activity destroy. Without it the discard-on-sign-out argument above is only a reference
  drop and the previous user's DOM lingers until GC.
- **Detaches before re-attaching.** `AndroidView` parents the returned view in its own
  holder, so a second factory call must `(parent as? ViewGroup)?.removeView(it)` first or
  Android throws "the specified child already has a parent".

**`ShellScreen` splits.** The WebView load-error UI and its retry move into mail's
content composable — a native sub-app handles its own failures. `ConnectivityMonitor` stays
shell-level: it is generic and video needs it too. Push-health polling becomes a
`rememberPushHealth()` state holder. What remains is the session gate, top bar, push banner,
and route switch. The route switch wraps `Content()` in `key(route.id)` so switching
sub-apps can never reuse composition state positionally.

### 3. Identity

Authelia becomes the root credential; each backend keeps its own session:

```
     Authelia (AuthState: id + access + refresh)
              |  one interactive sign-in
      +-------+--------+
      | ID token       | ID token
      v                v
POST mail/api/auth/native    POST video/api/auth/native
      |                |
      v                v
 mail session      video session      <- opaque, backend-minted, per-service
 (7d, = cookie)    (native only)
```

One credential *type*, two backends. Mail's path is unchanged, and deliberately so: its
opaque session token doubles as the WebView cookie value
(`auth/WebCookies.kt:27-28`), which an OIDC access token cannot do.

Audience-scoped access tokens per service were considered and rejected: they assume Authelia
will mint audience-scoped tokens for a public client and that each backend validates them,
neither of which is verified, and they make mail and video asymmetric for no gain.

**Sign-in is SSO-only.** The password path is deleted: `LoginReq`,
`AuthRepository.login`, `Authenticator.login`, `LoginResult.InvalidCredentials`, and the
email/password halves of `LoginViewModel` and `LoginScreen`. This removes the only sign-in
that works when `auth.whitewolf.tech` is down but mail is up — acceptable for a
single-operator suite, and stated so the trade is explicit. `LoginResult.InvalidCredentials`
is also mapped in `LoginViewModel.completeSso` (`:123`); that arm goes with it.

**Structure.** `AppContainer`'s single `baseUrl` is deleted. Each registry entry carries its
own base URL from a `BuildConfig` field with a `-P` override, matching the pattern
`MAIL_BASE_URL` already follows in `app/build.gradle.kts`. Identity splits in two:

- `IdentityRepository` — owns the AppAuth `AuthState`, persisted through the existing
  `SecureStore`. Behind an interface, the way `SsoLogin` already hides AppAuth from
  `LoginViewModel`, so refresh logic stays JVM-testable. **Exactly one instance per
  process**, and **one in-flight refresh at a time** (a mutex or a shared `Deferred`).
  AppAuth's own `AuthState.performActionWithFreshTokens` coalesces concurrent callers on one
  instance, but the seam that makes this testable is also what would let a hand-rolled
  refresh path lose that guarantee — and with rotating tokens, two concurrent refreshes mean
  the second gets `invalid_grant` and signs the user out moments after the first succeeded.
- `BackendSession(subAppId, baseUrl)` — mints and holds one backend's opaque bearer via that
  backend's `/api/auth/native`. Also single-flight per backend. It does **not** seed
  cookies: that would make identity know mail is web-hosted, which is the coupling section 1
  exists to prevent. Instead it publishes its current token as a `StateFlow`, and the
  web-hosted sub-app seeds its own cookie from that.

`TokenStore`'s fixed keys (`auth.token`, `auth.expires`) become per-sub-app.

**Minting is on demand, not on entry.** `BackendSession.bearer()` mints lazily the first
time anything asks, from any thread, using the same code path as the 401 re-mint below. This
is not a convenience: minting "when the user opens the sub-app" deadlocks the product. Push
registration needs a bearer (`push/PushApiClient.kt:34` returns `false` without one), video's
primary entry is a notification, and a notification only arrives if registration succeeded —
so a sub-app the user has never manually opened would never register, never notify, and
never be opened. `PushReceiver` already builds the process-wide container on a background
thread, so it can mint.

**Refresh makes expiry invisible.** Today any 401 kills the session and shows the login
screen. New rule: a 401 from a backend triggers a silent re-mint — refresh the ID token via
`AuthState`, re-POST `/api/auth/native`, retry the call **once**. The single retry is what
keeps this from looping: a re-mint that itself 401s is a terminal state for that sub-app,
never a second refresh.

Which failure means what has to be exact, because this is the boundary between "the user
notices nothing" and "the user is logged out":

| Failure | Meaning | Result |
| --- | --- | --- |
| Backend 401, refresh succeeds, re-mint succeeds | Session aged out | Silent; retry the call once |
| Refresh fails `invalid_grant` / `invalid_client` / `unauthorized_client` | The suite credential is dead | **Root invalidation** — sign out with the existing notice |
| Refresh fails network / 5xx / IdP unreachable | Authelia is down, we are not signed out | **Per-sub-app failure** — that sub-app shows its error state; try again later |
| `/api/auth/native` returns 401 with a fresh ID token | Backend cannot verify our token (JWKS, audience) | Per-sub-app **unavailable**; not a sign-out |
| `/api/auth/native` returns 403 with a fresh ID token | Valid identity, no account on that service | Per-sub-app **unavailable**, with copy that says so |

AppAuth reports refresh failures as `AuthorizationException`, not `IOException`, so the
existing "unreachable ≠ signed out" rule has to be re-expressed in terms of the exception's
error code rather than its type; a naive `catch (IOException)` would sign users out during
an Authelia outage. The two per-sub-app rows above are why "only a failed refresh signs you
out" was too coarse: a backend that rejects a *fresh* token is a service problem, not an
identity problem.

Note also that AppAuth's `getNeedsTokenRefresh()` keys off **access**-token expiry, while
what we replay is the **ID** token. On a 401 the re-mint must force a refresh
(`setNeedsTokenRefresh(true)`, or check the ID token's own `exp`), or it will cheerfully
re-POST the same expired ID token and fail identically.

**Validation goes lazy and per-sub-app.** Validating every backend on every resume wastes
calls and lets a video outage bounce the user out of mail. Each sub-app validates on entry.
At launch the root identity gets a **keep-alive refresh that tolerates failure**: it renews
the credential when it can, and a network failure or an IdP outage leaves the session
alone — only `invalid_grant` at launch signs the user out. `SessionBus.loggedIn` stops
meaning "mail's token exists" (`AppContainer.kt:27`) and starts meaning "we hold a usable
identity" — the change that finally decouples the shell's signed-in state from mail.

**A re-minted session must reach the WebView.** This is the failure mode that makes the
whole re-mint path worse than useless if it is skipped. The mail SPA probes `/api/me` on
load and renders **its own** login form whenever that probe fails
(`email-client-maileroo/web/src/App.tsx:78,127`), and it does not re-probe when the cookie
changes. So the sequence "cold start with a server-revoked token → SPA shows its login card
→ shell validates → 401 → silent re-mint → cookie fixed" ends with the user staring at a
login form inside a shell that believes it is signed in. Today that same case bounces to the
native login and a fresh WebView is built on re-login, so the user recovers; the silent path
would strand them.

The fix is the `StateFlow` above: `MailSubApp` observes its `BackendSession` token, seeds
the cookie, and **reloads** the retained WebView whenever the token changes.
`wwtWake()` is not sufficient — once the SPA has flipped to its unauthenticated view there
is no mail list to refresh — so this is a `loadUrl`, not a wake. The first load of a sub-app
is likewise gated on a validated session rather than racing it.

**Sign-out spans both backends, and must fence the re-mint path.** Order still matters —
unregister push per sub-app using live bearers, then clear sessions, cookies, `AuthState`,
and the retained sub-app scopes, then flip the bus. The existing `beginSignOut`/`endSignOut`
gate must wrap the **whole** multi-service teardown, and `endSignOut` must run in a
`finally`: a 401 from the second backend's unregister would otherwise surface "your session
expired" on a deliberate sign-out, which is precisely the bug `auth/SessionBus.kt` was
written to prevent.

But suppressing the *notice* is no longer enough, and this is a security requirement rather
than a UX one. Sign-out runs on its own thread (`ui/ShellScreen.kt:178-201`) while
`PushReceiver` can be registering on another. Under the new rule that a 401 triggers a
refresh and a re-mint, that in-flight registration's 401 would mint a **fresh multi-day
session and cookie** — which can land *after* `logout()` cleared them, leaving a usable
bearer on the device with the login screen showing.

So identity carries a **generation counter**, incremented on every sign-out and
invalidation. `BackendSession` captures the generation when a mint begins and discards the
result if it no longer matches, and `beginSignOut` vetoes new mints outright rather than
only silencing their notices. A late mint becomes a no-op instead of a resurrection.

Because the refresh token is now a long-lived suite-wide credential rather than a
seven-day per-service one, sign-out should also **revoke it at Authelia** where the
provider exposes a revocation endpoint. Local teardown alone leaves a valid credential in
the IdP's store. (This is revocation of our own token, not RP-initiated logout — the
non-goal above still stands: the user's browser session is untouched.)

### 4. Push routing

**One UnifiedPush instance per sub-app.** `SubAppId.value` is the instance name. Verified
against connector 2.5.0 in the Gradle cache: `registerApp(Context, String instance,
ArrayList<String>, String)` and `unregisterApp(Context, String instance)` both exist;
`push/PushManager.kt:15` currently uses the default-instance overload. Each instance receives
its own endpoint from the distributor and registers it with its own backend, so
`PushApiClient` becomes per-sub-app instead of posting everything to the mail API.

`PushReceiver` already receives `instance` on every callback and discards it
(`push/PushReceiver.kt:15,38`); routing is largely a matter of using the argument that is
already there. No suite-wide push registry is needed — the concern that motivated listing
push as a "suite service" dissolves once instances are used.

Knock-ons: `PushManager.enable()` and `reregister()` loop over instances rather than acting
on one, and `PushEndpointStore`'s single `push.endpoint` key becomes per-instance so
sign-out can unregister each.

**Channels and notification IDs become the sub-app's business**, via `SubAppPush`. The
channel id is `SubAppId.value`, replacing the hardcoded `"mail"`
(`push/Notifications.kt:23`). The constant notification ID `1` (`:24`) goes: mail keeps one
collapsing notification, while video wants several ready videos to stack, and each sub-app
owns that policy rather than a shared `Notifications` object knowing both.

**Wake routing.** `WakeBus` gains a `SubAppId` key, so a video wake cannot refresh mail. A
tick for a sub-app that is not currently composed is observed when the user next opens it,
because the flow holds the latest value and `LaunchedEffect(tick, pageLoaded)` already
re-evaluates on load (`ui/SubAppWebView.kt:66-70`).

**Every wake bumps the tick — including the notify path — and `pending` is deleted.** Today
`signalWakeBackground()` sets `pending` without touching the tick, and `pending` is consumed
only on `ON_RESUME` (`ui/SubAppWebView.kt:77-85`). That is sound while there is one sub-app,
because reaching it always involves resuming the Activity. It breaks the moment the rule
below sends a notification while the app is foreground in a *different* sub-app: the user
taps launcher → mail, no `ON_RESUME` fires because the Activity never stopped, the tick
never moved, and they read a stale inbox. Keeping one keyed tick for both arms removes the
whole class of bug; sub-apps consume it gated on `RESUMED` (`repeatOnLifecycle`) so nothing
refreshes from the background.

**The foreground rule is refined.** `wakeAction(isForeground)` (`push/WakeBus.kt:11-12`)
today means foreground → refresh silently, background → notify. With two sub-apps,
"foreground" now includes "the user is watching a video when mail arrives", where a silent
refresh is invisible and the user never learns they have mail. The rule becomes: **notify
unless the target sub-app is the visible route.** The visible route is therefore an input to
`wakeAction`, and it must be published to a **process-scoped** holder on `WwtApp` alongside
`ForegroundTracker` — written on route change, cleared on stop. It cannot be read from
`ShellViewModel`: `PushReceiver` is a manifest-registered `BroadcastReceiver` with no
Activity and no access to Activity-scoped state.

**Notification taps carry a deep link as intent data, not extras.**
`push/Notifications.kt:43` builds its `PendingIntent` with request code `0` and
`FLAG_UPDATE_CURRENT`, and extras do not participate in `PendingIntent` equality — so two
"video ready" notifications would share one intent and the second would overwrite the
first's payload, sending both taps to the same video. The target is encoded as data
(`wwt://subapp/<subAppId>/<itemId>`), which fixes the collision and gives `SubAppHost` a
`Uri` to deliver. `MainActivity` gains `onNewIntent` so warm taps route as well as cold
ones, and a deep link arriving while signed out is held and applied after sign-in rather
than being replaced by last-used.

**Push health stays global.** `PushStatus` is a statement about the distributor, which is
shared across instances, so `PushStatusBus` and the banner are unchanged.

**Someone has to decode the payload, and it must not be the shell.** Today's wake body is
`{"type":"new_mail"}` (`email-client-maileroo/internal/push/notifier.go`) and is ignored
entirely; it carries no item id. `SubAppPush` therefore gains
`decode(ByteArray): WakePayload?`, so each sub-app owns its own wire format and the receiver
stays generic. The alternative — one suite-wide payload schema fixed in `docs/PUSH.md` — is
also acceptable, but it must be one or the other; leaving it unstated puts every sub-app's
wire format inside `PushReceiver`.

**The privacy stance is preserved, not traded.** `docs/PUSH.md` states that the push carries
no content, and `push/Notifications.kt` posts a deliberately generic "New mail". A
video-ready notification wants a title and a thumbnail. The resolution: the *push payload*
stays content-free — the wake carries only an item id — and the app fetches the title and
thumbnail from the video backend with its live bearer.

That fetch must **not** block the notification. `MessagingReceiver.onReceive` calls
`onMessage` synchronously inside a broadcast with a budget of roughly ten seconds, and the
fetch may itself trigger a re-mint. So: post the generic notification **immediately**, then
re-post with the same notification id to replace it once enrichment lands, and keep the
generic one when the fetch fails or the device is offline. The notification gets richer, it
is never delayed or lost, ntfy learns nothing, and the documented guarantee survives intact.
`docs/PUSH.md` is updated to say this explicitly.

**Per-sub-app channels change the "notifications blocked" check.**
`areWwtNotificationsEnabled` (`ui/ShellScreen.kt:259-264`) inspects the single
`Notifications.CHANNEL_ID`. With one channel per sub-app, blocked-ness is per channel: the
banner reports a problem when the app-level toggle is off, or when the channel belonging to
a registered sub-app is blocked.

## Error handling / edge cases

- **Refresh fails `invalid_grant`** → root invalidation, existing "session expired" notice.
- **Refresh fails network / 5xx (IdP outage)** → nobody is signed out; the affected sub-app
  shows its error state and retries later.
- **One backend down, the other up** → only the affected sub-app shows its error state;
  the launcher and the other sub-app are unaffected. Not signed out (non-401).
- **`/api/auth/native` rejects a fresh ID token (401/403)** → that sub-app is unavailable,
  with copy distinguishing "cannot verify" from "no account on this service". Never a
  sign-out: the identity is fine, the service is not.
- **Two backends 401 at once** → single-flight refresh means exactly one token request; the
  second mint waits on it rather than racing it into `invalid_grant`.
- **Deep link to an unknown `SubAppId`** → ignored, app opens to last-used. A future build's
  notification must not crash an older shell.
- **Deep link while signed out** → held in `SavedStateHandle` until sign-in completes, then
  applied; a process death during the Custom Tab sign-in must not lose it.
- **Deep link re-delivered after process death** → `getIntent()` returns the same data URI,
  so consumption is recorded and the link fires once, not on every restore.
- **Notification tap for a sub-app whose session is dead** → sub-app opens, mints on demand,
  and (for a web sub-app) reloads once the new token lands; the user sees a load, not a
  bounce to login and not a stranded SPA login form.
- **Sign-out while a push registration is in flight** → `beginSignOut` vetoes new mints and
  the generation counter discards any that completes late, so no session outlives the
  teardown; `endSignOut` runs in a `finally` so a throwing teardown cannot leave the gate
  raised and mute every real expiry notice thereafter.
- **Distributor missing or on the wrong server** → unchanged; existing `PushStatus` banner
  and `reregister()` behavior, now looped over instances.
- **Process death while in a sub-app** → route restored from `SavedStateHandle`; content is
  rebuilt (the retained scope is gone), which is the same reload the user gets on rotation.

## Testing

JVM unit tests (the existing `app/src/test` idiom — pure logic behind seams):

- `SubAppRegistry`: id uniqueness, `byId` resolution, unknown-id handling.
- Deep-link parsing: `wwt://subapp/<id>/<item>` → target and payload; malformed and
  unknown-id inputs.
- `wakeAction` with the new visible-route argument: the four combinations of
  foreground/background × target-visible/not.
- Keyed `WakeBus`: a tick for one sub-app does not reach another; **both** the notify and
  the silent arm bump the tick.
- Refresh-on-401: exactly one retry after a successful re-mint, and a re-mint that itself
  401s terminates rather than refreshing again.
- The failure taxonomy, one case per row of the table in section 3: `invalid_grant` → root
  invalidation; network/5xx during refresh → no sign-out; `/api/auth/native` 401 and 403
  with a fresh token → sub-app unavailable, session intact.
- Single-flight: two concurrent 401s from different backends produce exactly one token
  refresh.
- Mint-on-demand: a bearer is available to push registration for a sub-app that has never
  been opened.
- Sign-out teardown: ordering (unregister before clear); `endSignOut` running after a
  throwing teardown; and a mint that completes *after* sign-out is discarded by the
  generation counter.
- Deep link: parsed once, consumed once, held across sign-in.
- `MailWebSession` listener rebinding: state primed on attach, no writes from an unbound
  listener. This requires splitting the listener/state logic behind an interface over the
  WebView — as written the class holds `WebView` and `SwipeRefreshLayout` and is
  instrumented-only.

Instrumented tests (`app/src/androidTest`):

- Launcher renders one tile per registry entry; tapping opens that sub-app.
- Back from a sub-app returns to the launcher; back from the launcher exits.
- A re-minted session reloads the mail WebView rather than leaving the SPA's own login form
  on screen.

Retiring password login touches more of the suite than one assertion:
`ShellFlowTest.kt:14` retargets from `submit` to `sso`; `LoginScreenTest` loses its
email/password interactions; `LoginViewModelTest` loses its `submit()`/`InvalidCredentials`
cases; and `AuthRepositoryTest`'s `login()` coverage is replaced by the SSO and re-mint
paths.

**Instrumented tests need a way to be signed in.** Today a test can seed a bearer directly;
under this design a usable session also needs a refresh token, so `AppContainer` must expose
a test-only `IdentityRepository` seam that can be given a canned identity. Without it every
signed-in instrumented test requires a live interactive OIDC flow, which is not runnable in
CI.

Note that no WebView tests exist today (only `auth/SetCookieTest.kt` touches that area), so
retention behavior has no existing coverage to inherit and needs the unit tests above plus
manual verification on a device.

## Migration and release

- **Every user re-authenticates once.** Retiring password login forces it, so the legacy
  `auth.token` / `auth.expires` keys are cleared on first run of the new build.
- **The legacy push endpoint must be retired before that clear, or not at all.**
  Unregistering it needs the legacy bearer, and the backend only prunes an endpoint when the
  push server returns 404/410 — which ntfy does not do for an unsubscribed topic, so a dead
  row would sit in the registry forever. First run therefore does, in this order: if a
  legacy token and endpoint both exist, `POST /api/push/unregister` with the legacy bearer,
  then `UnifiedPush.unregisterApp(ctx)` for the default instance, then clear the legacy
  keys. This is the one piece of genuine migration code; it is deletable a release later.
- `unregisterApp(ctx, instance)` drops the saved distributor once the last instance goes, so
  `reregister()` must unregister **all** instances and then call `enable()` — which only
  re-saves the distributor when exactly one is installed. Existing behaviour, now on a wider
  path.
- The registry ships with one entry (mail) until project B, so the launcher shows a single
  tile and cold start goes straight to mail. The restructure is therefore releasable on its
  own, and behaviour for a mail-only user is unchanged apart from sign-in and one extra back
  press.
- `README.md` sign-in section and `docs/PUSH.md` are updated in the same PR: SSO-only
  sign-in, per-sub-app push instances, and the content-free-payload clarification.
  `SECURITY.md` and the paired repo's `docs/AUTH.md` change too — the device now stores a
  long-lived suite-wide refresh token rather than a seven-day per-service bearer, and
  `AUTH.md`'s "there is no refresh endpoint" is no longer true.
- **Contributors pointing a build at their own backend now also need an OIDC provider.**
  With password login gone, `-PmailBaseUrl` alone is not enough: `/api/auth/native` requires
  the backend to be configured with a native client, so `CONTRIBUTING.md` and the README's
  override section must say that an Authelia (or equivalent) is part of the minimum setup.

## Decisions recorded

| Decision | Rejected alternative | Why |
| --- | --- | --- |
| Sub-app behind a small interface | Sealed type + service bundles in `AppContainer`; or minimally widening the existing seams | The first concentrates every sub-app's knowledge in the container; the second leaves all seams mail-shaped and pays the same cost later with interest, while B–D build on top |
| Fully native video sub-app | Hosted SPA | Chosen by the product owner; playback quality, PiP, background audio and offline are the reason |
| Launcher + remember-last | Bottom navigation; top-bar switcher | Bottom nav caps around five destinations and gives video permanent chrome it does not need — video's primary entry is a notification. Launcher scales to the planned suite |
| Launcher always beneath | Launcher beneath only when navigated from | Consistency; makes the deep-link case need no special rule. Costs a mail-only user one extra back press |
| One credential type, two backends | Audience-scoped OIDC access tokens per service | Unverified Authelia behavior; mail must keep an opaque token anyway because it doubles as the WebView cookie |
| SSO-only sign-in | Keeping password login as a fallback | A password user has no OIDC credential and could not reach the video sub-app; two classes of session is worse than one removed feature |
| Hand-rolled navigation | `navigation-compose` | Flat routes, no nested stacks, manual-DI codebase; its costs (route persistence, `onNewIntent`, predictive back) are handled explicitly |
| `remember`-scoped retention | Activity `ViewModel` + `MutableContextWrapper` | Rotation already reloads today, so only the in-Activity round-trip needs solving; the wrapper adds leak surface for a problem that does not exist |
| Per-sub-app UnifiedPush instances | A suite-wide push registry service | The connector already supports instances and already reports them; each backend keeps its own endpoint |
| Content-free push payload, app-side enrichment | Putting the video title in the push | Preserves the documented guarantee that the push server never sees content |
| Mint backend sessions on demand | Mint on sub-app entry | Entry-minting deadlocks push: a sub-app reached only by notification never registers, so it never notifies, so it is never entered |
| Generation-fenced sign-out | The `beginSignOut` notice gate alone | The gate suppresses the notice but not the mint; an in-flight 401 could re-mint a live session after teardown |
| Identity publishes a token `StateFlow`; the sub-app seeds its own cookie and reloads | `BackendSession` seeds cookies directly | Keeps identity ignorant of mail being web-hosted, and a silent re-mint that never reloads strands the user on the SPA's own login form |
| One keyed tick for both wake arms | Keeping a separate per-sub-app `pending` flag | `pending` is consumed on `ON_RESUME`, which never fires when the user reaches a sub-app from the launcher inside a resumed Activity |
| Post the generic notification, then replace it when enrichment lands | Fetching title and thumbnail before posting | A synchronous broadcast has ~10s; a slow fetch would delay or lose the notification entirely |
