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

1. **Authelia issues refresh tokens to the public client `maileroo-mobile`.** The scope
   becomes `openid profile email groups offline_access` (`auth/OidcAuthService.kt:45`).
   Without refresh tokens the retained `AuthState` expires in about an hour and the silent
   re-mint below cannot work; identity would fall back to re-authenticating interactively
   whenever any backend session expires.
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
`Content()`. `OnBackPressedDispatcher` is LIFO, so a sub-app's own handler — the WebView
history walk today, a native list→player pop tomorrow — must be registered later to win.
Today's handler in `ui/SubAppWebView.kt:59-61` already relies on this ordering implicitly;
here it becomes a stated invariant.

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
  `LoginViewModel`, so refresh logic stays JVM-testable.
- `BackendSession(subAppId, baseUrl)` — mints and holds one backend's opaque bearer via that
  backend's `/api/auth/native`, and seeds cookies where the sub-app is web-hosted.

`TokenStore`'s fixed keys (`auth.token`, `auth.expires`) become per-sub-app.

**Refresh makes expiry invisible.** Today any 401 kills the session and shows the login
screen. New rule: a 401 from a backend triggers a silent re-mint — refresh the ID token via
`AuthState`, re-POST `/api/auth/native`, retry the call once. Only a failed *refresh* signs
the user out. There are therefore two levels of invalidation, and conflating them is the
mistake this design exists to avoid:

- **Per-sub-app invalidation** — that backend's session is dead; re-mint it. A video 401
  must not sign the user out of mail.
- **Root invalidation** — the refresh itself failed; the suite signs out and
  `SessionBus.invalidate()` shows the existing "session expired" notice.

**Validation goes lazy and per-sub-app.** Validating every backend on every resume wastes
calls and lets a video outage bounce the user out of mail. The root identity is checked at
launch; each sub-app validates on entry. `SessionBus.loggedIn` stops meaning "mail's token
exists" (`AppContainer.kt:27`) and starts meaning "we hold a usable identity" — the change
that finally decouples the shell's signed-in state from mail.

The existing rule that **only a 401 signs you out** is preserved throughout: `IOException`,
DNS failure and 5xx leave every session intact (`auth/AuthRepository.kt:76-79`).

**Sign-out spans both backends.** Order still matters — unregister push per sub-app using
live bearers, then clear sessions, cookies, `AuthState`, and the retained sub-app scopes,
then flip the bus. The existing `beginSignOut`/`endSignOut` gate must wrap the **whole**
multi-service teardown, and `endSignOut` must run in a `finally`: a 401 from the second
backend's unregister would otherwise surface "your session expired" on a deliberate
sign-out, which is precisely the bug `auth/SessionBus.kt` was written to prevent.

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

**Wake routing.** `WakeBus` gains a `SubAppId` key, so a video wake cannot refresh mail. The
existing `StateFlow` shape handles the awkward case for free: a tick for a sub-app that is
not currently composed is observed when the user next opens it, because the flow holds the
latest value and `LaunchedEffect(tick, pageLoaded)` already re-evaluates on load
(`ui/SubAppWebView.kt:66-70`). The background-wake `pending` flag becomes per-sub-app too.

**The foreground rule is refined.** `wakeAction(isForeground)` (`push/WakeBus.kt:11-12`)
today means foreground → refresh silently, background → notify. With two sub-apps,
"foreground" now includes "the user is watching a video when mail arrives", where a silent
refresh is invisible and the user never learns they have mail. The rule becomes: **notify
unless the target sub-app is the visible route.** `WwtApp.isForeground` is therefore joined
by the current route, read from `ShellViewModel`.

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

**The privacy stance is preserved, not traded.** `docs/PUSH.md` states that the push carries
no content, and `push/Notifications.kt` posts a deliberately generic "New mail". A
video-ready notification wants a title and a thumbnail. The resolution: the *push payload*
stays content-free — the wake carries only an item id — and the app fetches the title and
thumbnail from the video backend with its live bearer before posting the notification,
falling back to generic copy when that fetch fails or the device is offline. The
notification gets richer, ntfy learns nothing, and the documented guarantee survives intact.
`docs/PUSH.md` is updated to say this explicitly.

## Error handling / edge cases

- **Refresh fails mid-session** → root invalidation, existing "session expired" notice.
- **One backend down, the other up** → only the affected sub-app shows its error state;
  the launcher and the other sub-app are unaffected. Not signed out (non-401).
- **Deep link to an unknown `SubAppId`** → ignored, app opens to last-used. A future build's
  notification must not crash an older shell.
- **Deep link while signed out** → held until sign-in completes, then applied.
- **Notification tap for a sub-app whose session is dead** → sub-app opens, validates on
  entry, silently re-mints; the user sees a load, not a bounce to login.
- **Sign-out while a push registration is in flight** → the `beginSignOut` gate suppresses
  the resulting 401 notice; `endSignOut` runs in a `finally` so a throwing teardown cannot
  leave the gate raised and mute every real expiry notice thereafter.
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
- Keyed `WakeBus`: a tick for one sub-app does not reach another; pending is per-sub-app.
- Refresh-on-401: one retry after a successful re-mint; no retry loop when the re-mint
  fails; root invalidation only on refresh failure.
- Per-sub-app vs root invalidation: a video 401 leaves mail's session intact.
- Sign-out teardown: ordering (unregister before clear), and `endSignOut` running after a
  throwing teardown.
- `MailWebSession` listener rebinding: state primed on attach, no writes from an unbound
  listener.

Instrumented tests (`app/src/androidTest`):

- Launcher renders one tile per registry entry; tapping opens that sub-app.
- Back from a sub-app returns to the launcher; back from the launcher exits.
- `ShellFlowTest.kt:14` retargets from the `submit` tag to `sso` — it will otherwise fail
  the moment password login is deleted.

Note that no WebView tests exist today (only `auth/SetCookieTest.kt` touches that area), so
retention behavior has no existing coverage to inherit and needs the unit tests above plus
manual verification on a device.

## Migration and release

- **Every user re-authenticates once.** Retiring password login forces it, so the legacy
  `auth.token` / `auth.expires` keys are simply cleared on first run of the new build. No
  migration code.
- Push re-registers under a named instance on first run; the old default-instance
  registration is unregistered during that first pass so the backend does not accumulate a
  dead endpoint.
- The registry ships with one entry (mail) until project B, so the launcher shows a single
  tile and cold start goes straight to mail. The restructure is therefore releasable on its
  own, and behaviour for a mail-only user is unchanged apart from sign-in and one extra back
  press.
- `README.md` sign-in section and `docs/PUSH.md` are updated in the same PR: SSO-only
  sign-in, per-sub-app push instances, and the content-free-payload clarification.

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
