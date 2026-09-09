# Shell Restructure + Suite Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the mail-only WebView shell into a shell that can host any number of web or fully-native sub-apps, each with its own backend session, notification channel and push instance.

**Architecture:** A sub-app becomes an interface with a UI facet (`SubApp`) and a non-UI push facet (`SubAppPush`), constructed with its own dependencies so the shell never learns what kind of thing it is hosting. A launcher and a hand-rolled route replace `SubAppRegistry.default()` auto-opening. Authelia becomes the root credential and each backend mints its own opaque session from an ID token, with silent re-mint on 401. Push moves to one UnifiedPush instance per sub-app.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), OkHttp, kotlinx.serialization, AppAuth 0.11.x, UnifiedPush connector 2.5.0, AndroidX Security Crypto, JUnit4 + MockWebServer + coroutines-test. Manual DI via `AppContainer` — no DI framework. minSdk 29, target/compile 35, JVM 17.

**Spec:** `docs/superpowers/specs/2026-09-08-shell-restructure-suite-identity-design.md`

## Global Constraints

- **Only a 401 signs a user out.** `IOException`, DNS failure and 5xx leave every session intact. For refresh failures the equivalent test is the AppAuth error code, not the exception type: only `invalid_grant`, `invalid_client` and `unauthorized_client` are root invalidation.
- **`SubApp` stays UI-shaped.** No `baseUrl`, token, or HTTP client on the interface. Anything a sub-app needs is a constructor argument.
- **`SubAppId.value` is the single source for five strings:** last-used preference key, notification channel id, deep-link path segment, wake routing key, UnifiedPush instance name. Never write those literals separately.
- **Sub-app construction must be UI-free and safe off the main thread** — `PushReceiver` builds `AppContainer` on a background thread.
- **Notification payloads carry no content.** Enrichment is fetched by the app, never sent through ntfy.
- **Build and test locally with** `export ANDROID_HOME=$HOME/android-sdk` then `./gradlew :app:testDebugUnitTest`. Instrumented tests need a device and are not runnable in this sandbox — mark them written-but-unverified and say so.
- **Every task ends green.** `./gradlew :app:testDebugUnitTest` must pass before the commit step, not just the new test. Note that this compiles `src/main` too, so a task that deletes an API its callers still use is *not* green.
- **No blocking network on the main thread, ever.** Minting, refreshing and push (un)registration are OkHttp calls. `mint()` wraps its request in `withContext(Dispatchers.IO)`; sign-out keeps the background `Thread` it has today (`ui/ShellScreen.kt:184`). **Never `runBlocking` a mint on the main thread** — AppAuth delivers its token callback on the main looper, so that deadlocks.

## Prerequisite gate

**Phases 1–3 and 5 are unblocked. Phase 4 (identity) is blocked** on the two external prerequisites in the spec: Authelia issuing long-lived rotating refresh tokens to `maileroo-mobile` with `preferred_username` on refresh-issued ID tokens, and a `/api/auth/native`-shaped endpoint on any second backend. Do not start Task 14 until those are confirmed. If Authelia cannot meet the lifespan or rotation requirement, stop and re-read the spec's stated fallback (keep today's no-refresh behaviour) before proceeding — that changes Tasks 16–18.

## Execution order

Task numbers are stable identifiers, **not** the order to execute in. One dependency
forces a different order: Task 11 rewrites `WakeBus`'s API, and Task 9 wires a sub-app to
the new `wake` stream, so 11 must land first. Execute in this order:

**1, 2, 3, 4, 11, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21.**

Task 11 depends only on Task 1, and folds in the minimal `PushReceiver` patch needed to
keep the tree compiling, so it is safe to pull forward.

## File structure

| File | Responsibility |
| --- | --- |
| `subapp/SubAppId.kt` | The value class; the only place the id string is defined |
| `subapp/SubApp.kt` | `SubApp` UI interface + `SubAppHost` inbound-event seam |
| `subapp/SubAppPush.kt` | `WakePayload` + `SubAppPush` non-UI facet |
| `subapp/SubAppEntry.kt` | Pairs the two facets; what the registry holds |
| `subapp/SubAppRegistry.kt` | Instance (not object) built by `AppContainer`; `all()` / `byId()` |
| `subapp/SubAppScope.kt` | Per-sub-app retained scope, cleared on route change and sign-out |
| `subapp/mail/MailSubApp.kt` | Mail's `SubApp` — wraps the WebView content |
| `subapp/mail/MailContent.kt` | The extracted, deliberately mail-shaped WebView composable |
| `subapp/mail/MailWebSession.kt` | Retained WebView + state + rebindable listener |
| `subapp/mail/MailPush.kt` | Mail's `SubAppPush` — channel, notification, tap intent, decode |
| `ui/ShellRoute.kt` | `ShellRoute` sealed type |
| `ui/ShellViewModel.kt` | Route state over `SavedStateHandle`; deep-link hold/consume |
| `ui/LauncherScreen.kt` | Tile grid over `registry.all()` |
| `ui/PushHealth.kt` | `rememberPushHealth()` — the polling extracted from `ShellScreen` |
| `push/VisibleRoute.kt` | Process-scoped visible-route holder on `WwtApp` |
| `push/DeepLink.kt` | `wwt://subapp/<id>/<item>` build + parse |
| `push/PushMigration.kt` | One-shot legacy-endpoint retirement |
| `auth/Identity.kt` | `Identity` seam interface + `AuthStateIdentity` AppAuth implementation |
| `auth/IdentityRepository.kt` | Single-flight refresh, generation counter, persistence |
| `auth/BackendSession.kt` | Per-backend mint-on-demand, token `StateFlow`, 401 re-mint |
| `ui/ShellScreen.kt` | Reduced to session gate, top bar, banner, route switch |
| `AppContainer.kt` | Builds the registry and per-sub-app sessions; single `baseUrl` deleted |

---

## Phase 1 — The sub-app model

### Task 1: `SubAppId` and the `SubApp` interface

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/SubAppId.kt`
- Create: `app/src/main/java/tech/whitewolf/app/subapp/SubApp.kt`
- Test: `app/src/test/java/tech/whitewolf/app/subapp/SubAppIdTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `SubAppId(value: String)` with `SubAppId.parse(raw: String): SubAppId?`; `data class WakePayload(val subAppId: SubAppId, val itemId: String? = null)`; `interface SubApp { val id: SubAppId; val title: String; val icon: ImageVector; @Composable fun Content(host: SubAppHost, modifier: Modifier) }`; `interface SubAppHost { val deepLink: StateFlow<WakePayload?>; val wake: StateFlow<Long>; fun onDeepLinkHandled() }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAppIdTest {
    @Test fun parseAcceptsLowercaseAlphanumeric() {
        assertEquals(SubAppId("mail"), SubAppId.parse("mail"))
        assertEquals(SubAppId("video2"), SubAppId.parse("video2"))
    }

    // The id is a channel id, a preference key, a URI path segment and a
    // UnifiedPush instance name. Anything that could break one of those is rejected
    // at the boundary rather than corrupting a channel or a deep link later.
    @Test fun parseRejectsValuesUnsafeForItsFiveUses() {
        assertNull(SubAppId.parse(""))
        assertNull(SubAppId.parse("Mail"))
        assertNull(SubAppId.parse("mail/video"))
        assertNull(SubAppId.parse("mail video"))
        assertNull(SubAppId.parse("../etc"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export ANDROID_HOME=$HOME/android-sdk && ./gradlew :app:testDebugUnitTest --tests "*SubAppIdTest*"`
Expected: FAIL — unresolved reference `SubAppId`.

- [ ] **Step 3: Write the implementation**

`SubAppId.kt`:

```kotlin
package tech.whitewolf.app.subapp

/**
 * A sub-app's identity. One string with five jobs: the launcher's last-used
 * preference key, the notification channel id, the deep-link path segment, the wake
 * routing key, and the UnifiedPush instance name. Defined once here so those five
 * never drift apart.
 */
@JvmInline
value class SubAppId(val value: String) {
    override fun toString(): String = value

    companion object {
        private val safe = Regex("^[a-z0-9]{1,32}$")

        /** Null when [raw] would be unsafe in any of the five uses above. */
        fun parse(raw: String): SubAppId? = if (safe.matches(raw)) SubAppId(raw) else null
    }
}
```

`SubApp.kt`:

```kotlin
package tech.whitewolf.app.subapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow

/**
 * What arrived on the wire. Deliberately thin: a target and an optional item id. Defined
 * here rather than beside SubAppPush because SubAppHost carries it too.
 */
data class WakePayload(val subAppId: SubAppId, val itemId: String? = null)

/**
 * What the shell can say to a sub-app while it is on screen. Three events, settled
 * up front: widening this later is worse than getting it right now.
 */
interface SubAppHost {
    /** "Open this item" — set before the sub-app composes, cleared via [onDeepLinkHandled]. */
    val deepLink: StateFlow<WakePayload?>

    /**
     * "Something changed, refetch" — a monotonic counter, NOT an event stream.
     *
     * It must be level-triggered: a `Flow<Unit>` would either replay on every collect
     * (a spurious refresh each time the user re-enters the sub-app, and again whenever
     * the collecting effect restarts) or drop a tick that arrived while the sub-app was
     * not composed. A counter lets the sub-app remember what it has already seen, which
     * is how the existing `LaunchedEffect(tick, pageLoaded)` already behaves.
     */
    val wake: StateFlow<Long>

    fun onDeepLinkHandled()
}

/**
 * A sub-app as the shell sees it: an id, how to label it, and a screen. Deliberately
 * UI-shaped — no base URL, no token, no HTTP client. A sub-app is CONSTRUCTED with
 * what it needs, so the shell never learns that mail has an origin or that video has
 * a player.
 */
interface SubApp {
    val id: SubAppId
    val title: String
    val icon: ImageVector

    @Composable
    fun Content(host: SubAppHost, modifier: Modifier)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppIdTest*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp/SubAppId.kt \
        app/src/main/java/tech/whitewolf/app/subapp/SubApp.kt \
        app/src/test/java/tech/whitewolf/app/subapp/SubAppIdTest.kt
git commit -m "feat(subapp): SubAppId value type and the SubApp/SubAppHost interfaces"
```

---

### Task 2: The push facet and registry

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/SubAppPush.kt`
- Create: `app/src/main/java/tech/whitewolf/app/subapp/SubAppEntry.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/subapp/SubAppRegistry.kt` (full rewrite)
- Modify: `app/src/test/java/tech/whitewolf/app/subapp/SubAppRegistryTest.kt` (full rewrite)

**Interfaces:**
- Consumes: `SubAppId`, `SubApp` (Task 1).
- Produces: `interface SubAppPush { val channelId: String; val channelName: String; val channelDescription: String; fun decode(body: ByteArray): WakePayload?; fun notify(context: Context, payload: WakePayload); fun tapTarget(payload: WakePayload): String; fun tapUri(payload: WakePayload): Uri = Uri.parse(tapTarget(payload)) }`; `data class SubAppEntry(val ui: SubApp, val push: SubAppPush?)`; `class SubAppRegistry(entries: List<SubAppEntry>)` with `all(): List<SubAppEntry>`, `byId(id: SubAppId): SubAppEntry?`, `ids(): List<SubAppId>`.

- [ ] **Step 1: Write the failing test**

Replace the whole of `SubAppRegistryTest.kt`:

```kotlin
package tech.whitewolf.app.subapp

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeSubApp(override val id: SubAppId) : SubApp {
    override val title = id.value
    override val icon: ImageVector = Icons.Filled.Email
    @Composable override fun Content(host: SubAppHost, modifier: Modifier) = Unit
}

private fun entry(id: String) = SubAppEntry(FakeSubApp(SubAppId(id)), push = null)

class SubAppRegistryTest {
    @Test fun byIdFindsARegisteredSubApp() {
        val reg = SubAppRegistry(listOf(entry("mail"), entry("video")))
        assertEquals(SubAppId("video"), reg.byId(SubAppId("video"))?.ui?.id)
    }

    @Test fun byIdReturnsNullForAnUnknownSubApp() {
        // A newer build's notification must not crash an older shell.
        val reg = SubAppRegistry(listOf(entry("mail")))
        assertNull(reg.byId(SubAppId("video")))
    }

    @Test fun allPreservesRegistrationOrder() {
        val reg = SubAppRegistry(listOf(entry("mail"), entry("video")))
        assertEquals(listOf(SubAppId("mail"), SubAppId("video")), reg.ids())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsAreRejectedAtConstruction() {
        // Two sub-apps sharing an id would collide on channel, prefs key and push instance.
        SubAppRegistry(listOf(entry("mail"), entry("mail")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppRegistryTest*"`
Expected: FAIL — `SubAppRegistry` constructor and `SubAppEntry` unresolved.

- [ ] **Step 3: Write the implementation**

`SubAppPush.kt`:

```kotlin
package tech.whitewolf.app.subapp

import android.content.Context
import android.net.Uri

/**
 * A sub-app's push behaviour, kept separate from [SubApp] because PushReceiver is a
 * BroadcastReceiver with no Activity and cannot touch a @Composable. Each sub-app owns
 * its own wire format, channel and notification ids.
 */
interface SubAppPush {
    val channelId: String          // always SubAppId.value
    val channelName: String
    val channelDescription: String

    /** Parse this sub-app's own payload. Null when the body is not understood. */
    fun decode(body: ByteArray): WakePayload?

    /** Post (or replace) this sub-app's notification. Owns its notification ids. */
    fun notify(context: Context, payload: WakePayload)

    /** Where a tap should land, as a string: wwt://subapp/<id>[/<item>]. */
    fun tapTarget(payload: WakePayload): String

    /** The same target as a Uri. Default impl; android.net.Uri is stubbed in unit tests,
     *  so tests assert on [tapTarget] instead. */
    fun tapUri(payload: WakePayload): Uri = Uri.parse(tapTarget(payload))
}
```

`SubAppEntry.kt`:

```kotlin
package tech.whitewolf.app.subapp

/** One sub-app's two facets. [push] is null for a sub-app that never notifies. */
data class SubAppEntry(val ui: SubApp, val push: SubAppPush?) {
    val id: SubAppId get() = ui.id
}
```

`SubAppRegistry.kt` (full replacement):

```kotlin
package tech.whitewolf.app.subapp

/**
 * The ordered registry of WWT sub-apps. An instance, not an object: entries are
 * constructed with their dependencies by AppContainer. Must stay UI-free and safe to
 * build off the main thread — PushReceiver builds the container on a background thread.
 *
 * There is deliberately no default()/auto-open: what opens on cold start is the
 * launcher's last-used state, not a property of the registry.
 */
class SubAppRegistry(private val entries: List<SubAppEntry>) {
    init {
        val ids = entries.map { it.id }
        require(ids.toSet().size == ids.size) { "duplicate sub-app ids: $ids" }
    }

    fun all(): List<SubAppEntry> = entries

    fun ids(): List<SubAppId> = entries.map { it.id }

    fun byId(id: SubAppId): SubAppEntry? = entries.firstOrNull { it.id == id }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppRegistryTest*"`
Expected: PASS, 4 tests.

The name `SubApp` is now an interface, but the old data class still has live callers
that need `.url`, `.title` and `.host` — `AppContainer.kt:24`, `ui/ShellScreen.kt:206,223`,
and `ui/SubAppWebView.kt:125,165`. A `SubAppEntry` has none of those, so it cannot stand in.

**Rename the old data class to `MailTarget` instead**, keeping it byte-identical
otherwise, and update those call sites plus the old `SubAppRegistryTest` construction. It
is a placeholder that Task 9 deletes when `MailSubApp` takes over. This keeps the tree
compiling with a three-line change rather than a broken build carried across two tasks.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp/ \
        app/src/test/java/tech/whitewolf/app/subapp/SubAppRegistryTest.kt \
        app/src/main/java/tech/whitewolf/app/AppContainer.kt \
        app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt
git commit -m "feat(subapp): SubAppPush facet, SubAppEntry, and an instance registry"
```

---

### Task 3: `MailWebSession` — retained WebView state behind a testable seam

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailWebSession.kt`
- Test: `app/src/test/java/tech/whitewolf/app/subapp/mail/MailWebSessionTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `interface WebViewHandle { fun canGoBack(): Boolean; fun goBack(); fun reload(); fun loadUrl(url: String); fun evaluateJavascript(script: String); fun onPause(); fun onResume(); fun destroy() }`; `interface SessionListener { fun onPageFinished(); fun onHistoryChanged(canGoBack: Boolean); fun onMainFrameError() }`; `class MailWebSession(val web: WebViewHandle)` exposing `pageLoaded`/`canGoBack`/`errored` as read-only `StateFlow<Boolean>` (private mutable backing), the retained fields `var container: ViewGroup?`, `var seededToken: String?`, `var lastWakeSeen: Long`, and `bind(listener)`, `unbind()`, `onAttached()`, `onDetached()`, `notifyPageFinished()`, `notifyHistoryChanged()`, `notifyMainFrameError()`, `destroy()`.

  The three retained fields and `destroy()` are consumed by Tasks 8, 9 and 19 — a `remember`-scoped equivalent would reload the SPA on every launcher round-trip, which is the defect this whole retention design exists to prevent.

The `WebViewHandle` indirection is what makes this JVM-testable. Holding a raw `WebView`
here would make every retention test instrumented-only, and retention has no existing
coverage to inherit.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.subapp.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeWeb(var history: Boolean = false) : WebViewHandle {
    var reloads = 0
    var loaded: String? = null
    val js = mutableListOf<String>()
    var paused = 0
    var resumed = 0
    var destroyed = false
    override fun canGoBack() = history
    override fun goBack() {}
    override fun reload() { reloads++ }
    override fun loadUrl(url: String) { loaded = url }
    override fun evaluateJavascript(script: String) { js += script }
    override fun onPause() { paused++ }
    override fun onResume() { resumed++ }
    override fun destroy() { destroyed = true }
}

class MailWebSessionTest {
    @Test fun attachPrimesCanGoBackFromTheLiveWebView() {
        // The retained client keeps browsing history across a launcher round-trip, but a
        // fresh composition starts at canGoBack=false. Without priming, back would leave
        // the sub-app mid-history.
        val web = FakeWeb(history = true)
        val s = MailWebSession(web)
        assertFalse(s.canGoBack.value)
        s.onAttached()
        assertTrue(s.canGoBack.value)
    }

    @Test fun attachDoesNotResetPageLoaded() {
        // Pull-to-refresh reads pageLoaded; if re-attach reset it, refresh would call
        // reload() — the exact reload retention exists to prevent.
        val s = MailWebSession(FakeWeb())
        s.bind(object : SessionListener {
            override fun onPageFinished() {}
            override fun onHistoryChanged(canGoBack: Boolean) {}
            override fun onMainFrameError() {}
        })
        s.notifyPageFinished()
        s.unbind()
        s.onDetached()
        s.onAttached()
        assertTrue(s.pageLoaded.value)
    }

    @Test fun anUnboundListenerReceivesNothing() {
        var finishes = 0
        val s = MailWebSession(FakeWeb())
        val l = object : SessionListener {
            override fun onPageFinished() { finishes++ }
            override fun onHistoryChanged(canGoBack: Boolean) {}
            override fun onMainFrameError() {}
        }
        s.bind(l)
        s.notifyPageFinished()
        s.unbind()
        s.notifyPageFinished()
        assertEquals(1, finishes)
    }

    @Test fun detachPausesAndAttachResumesTheWebView() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.onDetached()
        s.onAttached()
        assertEquals(1, web.paused)
        assertEquals(1, web.resumed)
    }

    @Test fun errorIsRecordedSoTheHostCanSuppressHistoryBack() {
        val s = MailWebSession(FakeWeb(history = true))
        s.notifyMainFrameError()
        assertTrue(s.errored.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MailWebSessionTest*"`
Expected: FAIL — unresolved `MailWebSession`, `WebViewHandle`, `SessionListener`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.subapp.mail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The slice of WebView this session drives. An interface so the session is JVM-testable. */
interface WebViewHandle {
    fun canGoBack(): Boolean
    fun goBack()
    fun reload()
    fun loadUrl(url: String)
    fun evaluateJavascript(script: String)
    fun onPause()
    fun onResume()
    fun destroy()
}

/** Callbacks a composition binds while it is on screen. */
interface SessionListener {
    fun onPageFinished()
    fun onHistoryChanged(canGoBack: Boolean)
    fun onMainFrameError()
}

/**
 * The retained half of a hosted web sub-app: the WebView plus the state its client
 * writes. Retaining a bare WebView does not work — its WebViewClient closures capture
 * composable-local state, so re-attaching into a fresh composition leaves the client
 * writing to a dead composition (canGoBack resets, pull-to-refresh sees pageLoaded=false
 * and reloads). Here the state lives with the session and the listener is rebound.
 */
class MailWebSession(val web: WebViewHandle) {
    /**
     * The view actually handed to AndroidView. Retained here so a second composition
     * re-attaches the same one; the factory must detach it from its previous parent first.
     * Set by the composable that builds it (Task 8).
     */
    var container: android.view.ViewGroup? = null

    /**
     * The session token whose cookie is currently seeded, and the highest wake tick already
     * applied. Both live HERE rather than in composition state: a `remember`-scoped copy
     * resets on every launcher round-trip, which would reload the SPA and re-fire wakes —
     * defeating the retention this class exists to provide.
     */
    var seededToken: String? = null
    var lastWakeSeen: Long = 0L

    private val _pageLoaded = MutableStateFlow(false)
    val pageLoaded: StateFlow<Boolean> = _pageLoaded

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val _errored = MutableStateFlow(false)
    val errored: StateFlow<Boolean> = _errored

    @Volatile private var listener: SessionListener? = null

    fun bind(l: SessionListener) { listener = l }

    fun unbind() { listener = null }

    /** Re-entering composition: prime from the live view, resume timers. */
    fun onAttached() {
        _canGoBack.value = web.canGoBack()
        web.onResume()
    }

    /** Leaving composition (launcher, another sub-app): stop JS timers. */
    fun onDetached() {
        web.onPause()
    }

    fun notifyPageFinished() {
        _pageLoaded.value = true
        _errored.value = false
        listener?.onPageFinished()
    }

    fun notifyHistoryChanged() {
        val v = web.canGoBack()
        _canGoBack.value = v
        listener?.onHistoryChanged(v)
    }

    fun notifyMainFrameError() {
        _errored.value = true
        listener?.onMainFrameError()
    }

    fun destroy() {
        unbind()
        web.destroy()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*MailWebSessionTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp/mail/MailWebSession.kt \
        app/src/test/java/tech/whitewolf/app/subapp/mail/MailWebSessionTest.kt
git commit -m "feat(subapp): retained MailWebSession with a rebindable listener"
```

---

### Task 4: `SubAppScope` — per-sub-app retention, discarded on sign-out

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/SubAppScope.kt`
- Test: `app/src/test/java/tech/whitewolf/app/subapp/SubAppScopeTest.kt`

**Interfaces:**
- Consumes: `SubAppId` (Task 1).
- Produces: `interface Retained { fun onDiscard() }`; `class SubAppScopes` with `<T : Retained> getOrPut(id: SubAppId, create: () -> T): T`, `discard(id: SubAppId)`, `discardAll()`.

The scope belongs to the sub-app, not the shell: a shell-level `RetainedWebViews` would
mean the shell learning that sub-apps have WebViews. `discardAll()` on sign-out is a
security requirement — a surviving scope would re-attach the previous user's live DOM and
`localStorage` under a different session cookie.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class Thing(val tag: String) : Retained {
    var discarded = false
    override fun onDiscard() { discarded = true }
}

class SubAppScopeTest {
    @Test fun theSameInstanceIsReturnedAcrossRoundTrips() {
        val s = SubAppScopes()
        val a = s.getOrPut(SubAppId("mail")) { Thing("a") }
        val b = s.getOrPut(SubAppId("mail")) { Thing("b") }
        assertSame(a, b)
    }

    @Test fun subAppsDoNotShareRetainedState() {
        val s = SubAppScopes()
        val mail = s.getOrPut(SubAppId("mail")) { Thing("mail") }
        val video = s.getOrPut(SubAppId("video")) { Thing("video") }
        assertNotSame(mail, video)
    }

    @Test fun discardCallsOnDiscardAndForcesRecreation() {
        val s = SubAppScopes()
        val first = s.getOrPut(SubAppId("mail")) { Thing("first") }
        s.discard(SubAppId("mail"))
        assertTrue(first.discarded)
        val second = s.getOrPut(SubAppId("mail")) { Thing("second") }
        assertEquals("second", second.tag)
    }

    @Test fun discardAllDiscardsEverySubApp() {
        // Sign-out must not leave the previous user's DOM attachable.
        val s = SubAppScopes()
        val mail = s.getOrPut(SubAppId("mail")) { Thing("mail") }
        val video = s.getOrPut(SubAppId("video")) { Thing("video") }
        s.discardAll()
        assertTrue(mail.discarded)
        assertTrue(video.discarded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppScopeTest*"`
Expected: FAIL — unresolved `SubAppScopes`, `Retained`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.subapp

/** Something a sub-app keeps across a launcher round-trip and must release on discard. */
interface Retained {
    fun onDiscard()
}

/**
 * Per-sub-app retained state, so navigating to the launcher and back does not rebuild a
 * sub-app's content. Owned by the shell but keyed by sub-app, so the shell never learns
 * what is being retained: mail keeps a MailWebSession here, video will keep its list
 * state and MediaController binding.
 *
 * discardAll() runs on sign-out. That is a security requirement, not hygiene — a scope
 * that outlived sign-out would re-attach the previous user's live DOM and localStorage
 * under a different session cookie.
 */
class SubAppScopes {
    private val held = mutableMapOf<SubAppId, Retained>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Retained> getOrPut(id: SubAppId, create: () -> T): T =
        held.getOrPut(id, create) as T

    fun discard(id: SubAppId) {
        held.remove(id)?.onDiscard()
    }

    fun discardAll() {
        val all = held.values.toList()
        held.clear()
        all.forEach { it.onDiscard() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppScopeTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp/SubAppScope.kt \
        app/src/test/java/tech/whitewolf/app/subapp/SubAppScopeTest.kt
git commit -m "feat(subapp): per-sub-app retained scopes, discarded on sign-out"
```

---

## Phase 2 — Navigation

### Task 5: Deep-link URIs

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/DeepLink.kt`
- Test: `app/src/test/java/tech/whitewolf/app/push/DeepLinkTest.kt`

**Interfaces:**
- Consumes: `SubAppId`, `WakePayload` (both Task 1).
- Produces: `object DeepLink { const val SCHEME = "wwt"; fun buildString(payload: WakePayload): String; fun parseString(raw: String?): WakePayload?; fun build(payload: WakePayload): Uri; fun parse(uri: Uri?): WakePayload? }`.

  The **String** forms are the primitives and the tested surface — `android.net.Uri` is stubbed under `isReturnDefaultValues`, so `Uri.parse` returns null in unit tests. Task 13 calls `buildString` directly.

Encoded as intent **data**, not extras: extras do not participate in `PendingIntent`
equality, so with request code `0` and `FLAG_UPDATE_CURRENT` two notifications would
share one intent and the second would overwrite the first's payload.

`android.net.Uri` is stubbed in unit tests, so parse/build work on strings internally and
the test asserts on `toString()`. Add `testOptions { unitTests.isReturnDefaultValues = true }`
if it is not already set — check `app/build.gradle.kts` first and skip if present.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun buildsATargetOnlyLink() {
        assertEquals("wwt://subapp/mail", DeepLink.buildString(WakePayload(SubAppId("mail"))))
    }

    @Test fun buildsAnItemLink() {
        assertEquals(
            "wwt://subapp/video/abc123",
            DeepLink.buildString(WakePayload(SubAppId("video"), "abc123")),
        )
    }

    @Test fun roundTripsBothShapes() {
        assertEquals(WakePayload(SubAppId("mail")), DeepLink.parseString("wwt://subapp/mail"))
        assertEquals(
            WakePayload(SubAppId("video"), "abc123"),
            DeepLink.parseString("wwt://subapp/video/abc123"),
        )
    }

    @Test fun rejectsAnythingItDidNotWrite() {
        assertNull(DeepLink.parseString(null))
        assertNull(DeepLink.parseString("https://mail.whitewolf.tech/inbox"))
        assertNull(DeepLink.parseString("wwt://other/mail"))
        assertNull(DeepLink.parseString("wwt://subapp/"))
        // An id that is unsafe as a channel id or instance name never becomes a target.
        assertNull(DeepLink.parseString("wwt://subapp/Mail"))
    }

    @Test fun itemIdIsPercentEncodedAndDecoded() {
        val p = WakePayload(SubAppId("video"), "a b/c")
        assertEquals(p, DeepLink.parseString(DeepLink.buildString(p)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*DeepLinkTest*"`
Expected: FAIL — unresolved `DeepLink`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.push

import android.net.Uri
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Notification targets, as `wwt://subapp/<subAppId>[/<itemId>]`.
 *
 * The target rides in the intent's DATA rather than its extras. Extras do not
 * participate in PendingIntent equality, so two notifications built with the same
 * request code and FLAG_UPDATE_CURRENT would share one intent and the second would
 * silently overwrite the first's payload — every tap landing on the same item.
 *
 * String forms are the primitive so the whole thing is JVM-testable; android.net.Uri is
 * stubbed in unit tests.
 */
object DeepLink {
    const val SCHEME = "wwt"
    private const val AUTHORITY = "subapp"
    private const val PREFIX = "$SCHEME://$AUTHORITY/"

    fun buildString(payload: WakePayload): String {
        val item = payload.itemId
        val tail = if (item.isNullOrEmpty()) "" else "/" + URLEncoder.encode(item, "UTF-8")
        return PREFIX + payload.subAppId.value + tail
    }

    fun build(payload: WakePayload): Uri = Uri.parse(buildString(payload))

    fun parseString(raw: String?): WakePayload? {
        val s = raw ?: return null
        if (!s.startsWith(PREFIX)) return null
        val rest = s.removePrefix(PREFIX)
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        val idPart = if (slash < 0) rest else rest.substring(0, slash)
        val id = SubAppId.parse(idPart) ?: return null
        val item = if (slash < 0 || slash == rest.lastIndex) null
        else URLDecoder.decode(rest.substring(slash + 1), "UTF-8")
        return WakePayload(id, item)
    }

    fun parse(uri: Uri?): WakePayload? = parseString(uri?.toString())
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*DeepLinkTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/DeepLink.kt \
        app/src/test/java/tech/whitewolf/app/push/DeepLinkTest.kt
git commit -m "feat(push): wwt://subapp deep-link URIs for notification targets"
```

---

### Task 6: `ShellRoute` and the route state holder

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/ui/ShellRoute.kt`
- Create: `app/src/main/java/tech/whitewolf/app/ui/RouteState.kt`
- Test: `app/src/test/java/tech/whitewolf/app/ui/RouteStateTest.kt`

**Interfaces:**
- Consumes: `SubAppId`, `WakePayload` (both Task 1); `DeepLink` (Task 5).
- Produces: `sealed interface ShellRoute { data object Launcher; data class Open(val id: SubAppId) }`; `class RouteState(known: (SubAppId) -> Boolean, lastUsed: SubAppId?, saved: SubAppId?)` with `route: StateFlow<ShellRoute>`, `pendingLink: StateFlow<WakePayload?>`, `open(id)`, `toLauncher()`, `offerLink(payload, signedIn)`, `onSignedIn()`, `consumeLink()`, `restoreKey: String?`.

Pure state, no Android types, so every rule below is a JVM test. `ShellViewModel` (Task 9)
is a thin `SavedStateHandle` wrapper over this.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.ui

import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val mail = SubAppId("mail")
private val video = SubAppId("video")
private val known: (SubAppId) -> Boolean = { it == mail || it == video }

class RouteStateTest {
    @Test fun coldStartOpensTheLastUsedSubApp() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        assertEquals(ShellRoute.Open(mail), s.route.value)
    }

    @Test fun coldStartWithNoHistoryShowsTheLauncher() {
        val s = RouteState(known, lastUsed = null, saved = null)
        assertEquals(ShellRoute.Launcher, s.route.value)
    }

    @Test fun aSavedRouteBeatsLastUsedAfterProcessDeath() {
        val s = RouteState(known, lastUsed = mail, saved = video)
        assertEquals(ShellRoute.Open(video), s.route.value)
    }

    @Test fun anUninstalledLastUsedFallsBackToTheLauncher() {
        // A sub-app removed in a later build must not strand the user on a blank route.
        val s = RouteState(known, lastUsed = SubAppId("gone"), saved = null)
        assertEquals(ShellRoute.Launcher, s.route.value)
    }

    @Test fun aDeepLinkWhileSignedInOpensItsTargetImmediately() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        s.offerLink(WakePayload(video, "v1"), signedIn = true)
        assertEquals(ShellRoute.Open(video), s.route.value)
        assertEquals(WakePayload(video, "v1"), s.pendingLink.value)
    }

    @Test fun aDeepLinkWhileSignedOutIsHeldAndAppliedAfterSignIn() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        s.offerLink(WakePayload(video, "v1"), signedIn = false)
        assertEquals(ShellRoute.Open(mail), s.route.value)  // not yet navigated
        s.onSignedIn()
        assertEquals(ShellRoute.Open(video), s.route.value)
    }

    @Test fun aDeepLinkForAnUnknownSubAppIsIgnored() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        s.offerLink(WakePayload(SubAppId("ghost"), "x"), signedIn = true)
        assertEquals(ShellRoute.Open(mail), s.route.value)
        assertNull(s.pendingLink.value)
    }

    @Test fun consumeClearsThePendingLink() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        val p = WakePayload(video, "v1")
        s.offerLink(p, signedIn = true)
        assertEquals(p, s.consumeLink())
        assertNull(s.pendingLink.value)
    }

    @Test fun openRecordsTheRestoreKey() {
        val s = RouteState(known, lastUsed = null, saved = null)
        s.open(video)
        assertEquals("video", s.restoreKey)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RouteStateTest*"`
Expected: FAIL — unresolved `RouteState`, `ShellRoute`.

- [ ] **Step 3: Write the implementation**

`ShellRoute.kt`:

```kotlin
package tech.whitewolf.app.ui

import tech.whitewolf.app.subapp.SubAppId

/** Where the shell is. Flat by design: no nested stacks, so no nav library. */
sealed interface ShellRoute {
    data object Launcher : ShellRoute
    data class Open(val id: SubAppId) : ShellRoute
}
```

`RouteState.kt`:

```kotlin
package tech.whitewolf.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload

/**
 * The shell's navigation, as pure state. Android-free so every rule is a JVM test;
 * ShellViewModel is a thin SavedStateHandle wrapper over this.
 *
 * Cold start opens the last-used sub-app so mail stays zero-tap for a mail-only user,
 * but the launcher always sits BENEATH a sub-app — including a deep-linked one — so back
 * needs no special case.
 */
class RouteState(
    private val known: (SubAppId) -> Boolean,
    lastUsed: SubAppId?,
    saved: SubAppId?,
) {
    private val initial: ShellRoute = (saved ?: lastUsed)
        ?.takeIf(known)
        ?.let { ShellRoute.Open(it) }
        ?: ShellRoute.Launcher

    private val _route = MutableStateFlow(initial)
    val route: StateFlow<ShellRoute> = _route

    private val _pendingLink = MutableStateFlow<WakePayload?>(null)
    val pendingLink: StateFlow<WakePayload?> = _pendingLink

    /** Held while signed out; applied by [onSignedIn]. */
    private var heldLink: WakePayload? = null

    /** The id to persist as "last used", or null at the launcher. */
    val restoreKey: String? get() = (_route.value as? ShellRoute.Open)?.id?.value

    fun open(id: SubAppId) {
        if (known(id)) _route.value = ShellRoute.Open(id)
    }

    fun toLauncher() { _route.value = ShellRoute.Launcher }

    /**
     * A notification tap. Ignored for an unknown sub-app so an older shell survives a
     * newer build's notification. While signed out it is held rather than dropped —
     * the user finishes signing in and lands where they tapped.
     */
    fun offerLink(payload: WakePayload, signedIn: Boolean) {
        if (!known(payload.subAppId)) return
        if (signedIn) {
            _pendingLink.value = payload
            _route.value = ShellRoute.Open(payload.subAppId)
        } else {
            heldLink = payload
        }
    }

    fun onSignedIn() {
        val held = heldLink ?: return
        heldLink = null
        offerLink(held, signedIn = true)
    }

    /** Take the link exactly once — getIntent() re-delivers the same URI after process death. */
    fun consumeLink(): WakePayload? {
        val p = _pendingLink.value
        _pendingLink.value = null
        return p
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*RouteStateTest*"`
Expected: PASS, 9 tests.

**On intent redelivery.** `getIntent()` returns the same data URI after process death, so a
naive re-offer would re-navigate on every restore. Do **not** solve that in `RouteState` by
remembering payloads already seen: mail's payload is always the identical
`WakePayload(mail, null)`, so a "seen" set would silently ignore every notification tap
after the first for the life of the process. It is an Activity concern instead — Task 9
delivers `intent.data` from `onCreate` only when `savedInstanceState == null`, and always
from `onNewIntent`. After process death the route is restored from `SavedStateHandle`
anyway, so the stale intent never needs replaying.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/ShellRoute.kt \
        app/src/main/java/tech/whitewolf/app/ui/RouteState.kt \
        app/src/test/java/tech/whitewolf/app/ui/RouteStateTest.kt
git commit -m "feat(shell): flat route state with held deep links and last-used cold start"
```

---

### Task 7: Last-used persistence and the launcher screen

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/ui/LastUsedStore.kt`
- Create: `app/src/main/java/tech/whitewolf/app/ui/LauncherScreen.kt`
- Test: `app/src/test/java/tech/whitewolf/app/ui/LastUsedStoreTest.kt`

**Interfaces:**
- Consumes: `SubAppId` (Task 1), `SubAppEntry` (Task 2).
- Produces: `interface Prefs { fun get(key: String): String?; fun put(key: String, value: String) }`; `class LastUsedStore(prefs: Prefs)` with `get(): SubAppId?`, `set(id: SubAppId)`; `@Composable fun LauncherScreen(entries: List<SubAppEntry>, onOpen: (SubAppId) -> Unit)`.

Plain `SharedPreferences` behind `Prefs`, not `SecureStore`: the last-used id is not a
secret, and `EncryptedSharedPreferences` is deprecated upstream — no reason to widen that
dependency for one string.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.ui

import tech.whitewolf.app.subapp.SubAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MapPrefs(vararg pairs: Pair<String, String>) : Prefs {
    val m = mutableMapOf(*pairs)
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
}

class LastUsedStoreTest {
    @Test fun roundTrips() {
        val p = MapPrefs()
        LastUsedStore(p).set(SubAppId("video"))
        assertEquals(SubAppId("video"), LastUsedStore(p).get())
    }

    @Test fun absentOnFirstRun() {
        assertNull(LastUsedStore(MapPrefs()).get())
    }

    @Test fun aCorruptStoredValueReadsAsAbsent() {
        // Never let a bad pref become a channel id or instance name.
        assertNull(LastUsedStore(MapPrefs("shell.lastUsed" to "../etc")).get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LastUsedStoreTest*"`
Expected: FAIL — unresolved `LastUsedStore`, `Prefs`.

- [ ] **Step 3: Write the implementation**

`LastUsedStore.kt`:

```kotlin
package tech.whitewolf.app.ui

import android.content.Context
import tech.whitewolf.app.subapp.SubAppId

/** Minimal key/value so the store is testable without Android. */
interface Prefs {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** Prefs over plain SharedPreferences — the last-used id is not a secret. */
class AndroidPrefs(context: Context) : Prefs {
    private val sp = context.applicationContext
        .getSharedPreferences("wwt.shell", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
}

/** Which sub-app to open on cold start. Re-validated on read, never trusted raw. */
class LastUsedStore(private val prefs: Prefs) {
    private val key = "shell.lastUsed"
    fun get(): SubAppId? = prefs.get(key)?.let { SubAppId.parse(it) }
    fun set(id: SubAppId) = prefs.put(key, id.value)
}
```

`LauncherScreen.kt`:

```kotlin
package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import tech.whitewolf.app.subapp.SubAppEntry
import tech.whitewolf.app.subapp.SubAppId

/** The suite home: one tile per registered sub-app, in registration order. */
@Composable
fun LauncherScreen(
    entries: List<SubAppEntry>,
    onOpen: (SubAppId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.id.value }) { entry ->
            Card(
                onClick = { onOpen(entry.id) },
                modifier = Modifier.testTag("tile.${entry.id.value}"),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(entry.ui.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(
                        entry.ui.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LastUsedStoreTest*"` then the full
`./gradlew :app:testDebugUnitTest`.
Expected: PASS, 3 new tests; suite green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/LastUsedStore.kt \
        app/src/main/java/tech/whitewolf/app/ui/LauncherScreen.kt \
        app/src/test/java/tech/whitewolf/app/ui/LastUsedStoreTest.kt
git commit -m "feat(shell): launcher screen and last-used persistence"
```

---

### Task 8: Extract `MailContent` from `SubAppWebView` and `ShellScreen`

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailContent.kt`
- Create: `app/src/main/java/tech/whitewolf/app/subapp/mail/AndroidWebViewHandle.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailWebSession.kt` — uses the
  `container` / `seededToken` / `lastWakeSeen` fields added in Task 3
- Delete: `app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` — remove the error screen, `errorMessageFor`, `reloadKey`, and the `SubAppWebView` call (lines ~211-260)
- Modify: `app/src/test/java/tech/whitewolf/app/ui/ErrorMessageTest.kt` — move to `subapp/mail/`, update package

**Interfaces:**
- Consumes: `MailWebSession`, `WebViewHandle`, `SessionListener` (Task 3); `SubAppHost` (Task 1).
- Produces: `class AndroidWebViewHandle(val view: WebView) : WebViewHandle`; `@Composable fun MailContent(session: MailWebSession, url: String, host: SubAppHost, online: Boolean, modifier: Modifier)`; `internal fun errorMessageFor(online: Boolean, title: String): String` (moved verbatim).

This is a move, not a rewrite. Keep every behaviour from `SubAppWebView.kt`: the settings
block, `NavPolicy` external-link handling, `SwipeRefreshLayout` with explicit
`MATCH_PARENT` params (the 2026-07-07 blank-app incident), the cookie-then-`loadUrl`
ordering, algorithmic darkening, and the `ShellBridge` JS interface. What changes is
where state lives — the `WebViewClient` now calls `session.notifyPageFinished()` /
`notifyHistoryChanged()` / `notifyMainFrameError()` instead of writing composable-local
`var`s, and the composable observes `session.pageLoaded` / `canGoBack` / `errored`.

The composable is deliberately **mail-shaped**, not a generic `WebSubAppContent`.
Generalising one implementation for one caller is speculation; sub-app #3 can generalise it.

- [ ] **Step 1: Write the failing test**

`errorMessageFor` already has coverage in `ui/ErrorMessageTest.kt`. Move that file to
`app/src/test/java/tech/whitewolf/app/subapp/mail/ErrorMessageTest.kt`, change its package
to `tech.whitewolf.app.subapp.mail`, and add one case for the new rule:

```kotlin
    @Test fun anErroredSessionSuppressesHistoryBack() {
        // canGoBack can be true while the error screen shows — a main-frame error on a
        // later navigation leaves earlier history intact. Back must reach the launcher,
        // not walk history behind an error screen.
        val session = MailWebSession(object : WebViewHandle {
            override fun canGoBack() = true
            override fun goBack() {}
            override fun reload() {}
            override fun loadUrl(url: String) {}
            override fun evaluateJavascript(js: String) {}
            override fun onPause() {}
            override fun onResume() {}
            override fun destroy() {}
        })
        session.onAttached()
        session.notifyMainFrameError()
        assertFalse(mailBackEnabled(session.canGoBack.value, session.errored.value))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ErrorMessageTest*"`
Expected: FAIL — unresolved `mailBackEnabled`.

- [ ] **Step 3: Write the implementation**

Add to `MailContent.kt` (the rule, extracted so it is testable):

```kotlin
/**
 * Whether mail's own back handler should be armed. History back is suppressed while the
 * load-error screen is up: canGoBack can still be true there, and walking history behind
 * an error screen instead of returning to the launcher reads as the app being stuck.
 */
internal fun mailBackEnabled(canGoBack: Boolean, errored: Boolean): Boolean =
    canGoBack && !errored
```

Then move the body of `SubAppWebView.kt` into `MailContent`, with these changes:

```kotlin
package tech.whitewolf.app.subapp.mail

import android.webkit.WebView
// ...remaining imports carried over from ui/SubAppWebView.kt

/** WebViewHandle over the real WebView. */
class AndroidWebViewHandle(val view: WebView) : WebViewHandle {
    override fun canGoBack() = view.canGoBack()
    override fun goBack() = view.goBack()
    override fun reload() = view.reload()
    override fun loadUrl(url: String) = view.loadUrl(url)
    override fun evaluateJavascript(js: String) = view.evaluateJavascript(js, null)
    override fun onPause() = view.onPause()
    override fun onResume() = view.onResume()
    override fun destroy() = view.destroy()
}
```

`MailContent` structure — carry the WebView setup over verbatim and change only these points:

1. `AndroidView(factory = ...)` returns the session's existing `SwipeRefreshLayout` when one
   is retained (`MailWebSession.container`, Task 3), detaching it from its previous parent
   first — `AndroidView` parents the view in its own holder, so re-attaching without this
   throws "the specified child already has a parent":
   ```kotlin
   AndroidView(factory = { ctx ->
       session.container?.also { existing ->
           (existing.parent as? ViewGroup)?.removeView(existing)
       } ?: buildContainer(ctx, session, url).also { session.container = it }
   })
   ```
   `buildContainer(ctx, session, url)` is the existing WebView + `SwipeRefreshLayout`
   construction moved verbatim, taking the `Context` from the factory.
2. The `WebViewClient` overrides call the session:
   ```kotlin
   override fun onPageFinished(view: WebView, url: String) { session.notifyPageFinished() }
   override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
       if (req.isForMainFrame) session.notifyMainFrameError()
   }
   override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
       session.notifyHistoryChanged()
   }
   ```
3. Attach/detach drive pause/resume:
   ```kotlin
   DisposableEffect(session) {
       session.onAttached()
       onDispose { session.unbind(); session.onDetached() }
   }
   ```
4. Back handler uses the extracted rule:
   ```kotlin
   val canGoBack by session.canGoBack.collectAsState()
   val errored by session.errored.collectAsState()
   BackHandler(enabled = mailBackEnabled(canGoBack, errored)) { session.web.goBack() }
   ```
5. The error screen (moved from `ShellScreen.kt:214-232`) renders when `errored`, with the
   same Retry button and `errorMessageFor(online, "Mail")` copy. Retry calls
   `session.web.reload()` rather than bumping a `reloadKey`.
6. Wake comes from `host.wake`, which is a level-triggered counter, not an event stream.
   The session remembers the highest tick applied so re-entering mail does not re-fire a
   wake already handled, and a tick that arrived while mail was not composed is applied on
   the next entry:
   ```kotlin
   val tick by host.wake.collectAsState()
   LaunchedEffect(tick, pageLoaded) {
       if (pageLoaded && tick > session.lastWakeSeen) {
           session.lastWakeSeen = tick
           session.web.evaluateJavascript(WAKE_JS)
       }
   }
   ```
7. The pull-to-refresh listener must read `session.pageLoaded.value`, not a captured
   composition-local — the listener outlives the composition that installed it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest` (full suite — this task deletes a file the shell
referenced).
Expected: PASS. Fix any compile breakage in `ShellScreen.kt` by deleting the error-screen
block and the `SubAppWebView` call; Task 9 wires the replacement.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/tech/whitewolf/app/subapp/mail/ \
           app/src/main/java/tech/whitewolf/app/ui/ \
           app/src/test/java/tech/whitewolf/app/
git commit -m "refactor(mail): extract MailContent from SubAppWebView and ShellScreen"
```

---

### Task 9: `MailSubApp`, `ShellViewModel`, and the shell route switch

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailSubApp.kt`
- Create: `app/src/main/java/tech/whitewolf/app/ui/ShellViewModel.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/MainActivity.kt` — `onNewIntent`
- Modify: `app/src/main/AndroidManifest.xml` — no change needed; `wwt://` links are
  delivered to `MainActivity` by our own `PendingIntent`, so **no intent-filter is added**
  (a filter would expose the deep link to other apps for no benefit)

**Interfaces:**
- Consumes: `SubApp`, `SubAppHost` (Task 1); `SubAppScopes` (Task 4); `RouteState`,
  `ShellRoute` (Task 6); `LastUsedStore` (Task 7); `MailContent`, `MailWebSession` (Tasks 3, 8).
- Produces: `class MailSubApp(url: String, scopes: SubAppScopes, token: () -> String?, online: () -> Boolean) : SubApp` (the `token` parameter becomes a `StateFlow<String?>` in Task 19); `class ShellViewModel(saved: SavedStateHandle, registry: SubAppRegistry, lastUsed: LastUsedStore) : ViewModel()` exposing `route`, `open(id)`, `toLauncher()`, `offerLink(uri, signedIn)`, `hostFor(id): SubAppHost`.

- [ ] **Step 1: Write the failing test**

Instrumented (`app/src/androidTest/java/tech/whitewolf/app/ShellNavTest.kt`) — this is UI
behaviour, so it cannot be a JVM test:

```kotlin
package tech.whitewolf.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavTest {
    // NOT createAndroidComposeRule<MainActivity>(): that launches the Activity before
    // @Before runs, so there is no window in which to clear prefs or seed a session.
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun reset() {
        // The tap test persists shell.lastUsed = mail, which would make the next cold
        // start open mail directly and hide the launcher. Tests must not depend on order.
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("wwt.shell", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun launcherShowsATilePerRegisteredSubApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("tile.mail").assertIsDisplayed()
        }
    }

    @Test fun tappingATileOpensThatSubApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("tile.mail").performClick()
            compose.onNodeWithTag("subapp.mail").assertIsDisplayed()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:assembleDebugAndroidTest` (compiles the test without a device).
Expected: FAIL — unresolved tags / `MainActivity` still auto-opens mail.
**This sandbox has no device**, so the test is written-but-unverified; say so in the
commit body and in your report.

- [ ] **Step 3: Write the implementation**

`MailSubApp.kt`:

```kotlin
package tech.whitewolf.app.subapp.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import tech.whitewolf.app.subapp.Retained
import tech.whitewolf.app.subapp.SubApp
import tech.whitewolf.app.subapp.SubAppHost
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.SubAppScopes

/** The retained half of mail, held in the sub-app's own scope. */
class MailScope(val session: MailWebSession) : Retained {
    override fun onDiscard() = session.destroy()
}

/**
 * Mail as a sub-app. Constructed with its URL and a token supplier — the shell never
 * learns mail has an origin.
 */
class MailSubApp(
    private val url: String,
    private val scopes: SubAppScopes,
    private val token: () -> String?,
    private val online: () -> Boolean,
) : SubApp {
    override val id = SubAppId("mail")
    override val title = "Mail"
    override val icon: ImageVector = Icons.Filled.Email

    @Composable
    override fun Content(host: SubAppHost, modifier: Modifier) {
        val scope = scopes.getOrPut(id) { MailScope(newSession(url, token())) }
        MailContent(
            session = scope.session,
            url = url,
            host = host,
            online = online(),
            modifier = modifier.testTag("subapp.${id.value}"),
        )
    }
}
```

`newSession(url, token)` builds the `WebView`, its `SwipeRefreshLayout` container and an
`AndroidWebViewHandle`, seeding the cookie before `loadUrl` exactly as `MailContent` does
today. Put it in `MailContent.kt` next to the view construction it shares.

`ShellViewModel.kt` wraps `RouteState`, persisting `restoreKey` into `SavedStateHandle`
on every route change and into `LastUsedStore` on every `open`, and builds one
`SubAppHost` per sub-app whose `wake` is that sub-app's `WakeBus` stream (Task 11) and
whose `deepLink` is `RouteState.pendingLink` filtered to that sub-app.

`ShellScreen.kt` becomes:

```kotlin
// ...session gate unchanged (loggedIn / LoginScreen)
val vm: ShellViewModel = viewModel(factory = ShellViewModelFactory(container))
val route by vm.route.collectAsState()

// Composed BEFORE Content(): OnBackPressedDispatcher is LIFO, so a sub-app's own
// handler (mail's history walk, a future list->player pop) must register later to win.
BackHandler(enabled = route is ShellRoute.Open) { vm.toLauncher() }

Scaffold(topBar = { TopAppBar(title = { Text(titleFor(route, container.registry)) }, actions = { signOutButton() }) }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
        pushBannerContent(pushStatus, notificationsEnabled)?.let { PushStatusBanner(it) }
        when (val r = route) {
            is ShellRoute.Launcher ->
                LauncherScreen(container.registry.all(), onOpen = vm::open)
            is ShellRoute.Open -> {
                val entry = container.registry.byId(r.id)
                if (entry == null) vm.toLauncher()
                else key(r.id.value) { entry.ui.Content(vm.hostFor(r.id), Modifier.fillMaxSize()) }
            }
        }
    }
}
```

`MainActivity` gains:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    deliverDeepLink(intent)
}
```

No process-scoped bridge is needed: `MainActivity` owns the view model
(`by viewModels { ShellViewModelFactory(container) }`) and calls it directly.

```kotlin
private val shellVm: ShellViewModel by viewModels { ShellViewModelFactory(container) }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Only on a genuinely new launch. After process death getIntent() re-delivers the
    // same data URI, and the route has already been restored from SavedStateHandle —
    // replaying it would re-navigate on every restore.
    if (savedInstanceState == null) deliverDeepLink(intent)
    ...
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    deliverDeepLink(intent)      // always: this is a fresh tap
}

private fun deliverDeepLink(intent: Intent) {
    DeepLink.parse(intent.data)?.let {
        shellVm.offerLink(it, signedIn = container.sessionBus.loggedIn.value)
    }
}
```

`ShellViewModelFactory` is a plain `ViewModelProvider.Factory` using
`AbstractSavedStateViewModelFactory` (or `viewModelFactory { initializer { ... } }` with
`createSavedStateHandle()`); `androidx.lifecycle:lifecycle-viewmodel-savedstate` is already
on the classpath transitively via `activity-compose`.

In the route switch, do not mutate route state during composition — an unknown id is
handled in a `LaunchedEffect`:

```kotlin
is ShellRoute.Open -> {
    val entry = container.registry.byId(r.id)
    if (entry == null) {
        LaunchedEffect(r.id) { vm.toLauncher() }
    } else {
        key(r.id.value) { entry.ui.Content(vm.hostFor(r.id), Modifier.fillMaxSize()) }
    }
}
```

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: unit suite PASS, debug APK builds. Instrumented test compiles via
`./gradlew :app:assembleDebugAndroidTest` but is unverified without a device.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/tech/whitewolf/app/ app/src/androidTest/java/tech/whitewolf/app/
git commit -m "feat(shell): launcher route switch, MailSubApp, and warm deep links

ShellNavTest is written but unverified — no device available in this environment."
```

---

### Task 10: Extract `rememberPushHealth` and finish the `ShellScreen` split

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/ui/PushHealth.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` — remove `recheck`, the
  `WrongServer` effect, the 30s poll, and `areWwtNotificationsEnabled` (lines ~76-150, 255-264)
- Test: `app/src/test/java/tech/whitewolf/app/ui/PushHealthTest.kt`

**Interfaces:**
- Consumes: `PushStatus`, `PushStatusBus`, `PushManager` (existing); `SubAppRegistry` (Task 2).
- Produces: `data class PushHealth(val status: PushStatus, val notificationsEnabled: Boolean)`; `@Composable fun rememberPushHealth(container: AppContainer): PushHealth`; `internal fun channelsBlocked(blocked: Set<String>, registered: List<String>): Boolean`.

`ConnectivityMonitor` **stays in `ShellScreen`** — it is generic and video needs it too.
Only the WebView error UI moved (Task 8).

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushHealthTest {
    @Test fun noBlockedChannelsIsHealthy() {
        assertFalse(channelsBlocked(blocked = emptySet(), registered = listOf("mail", "video")))
    }

    @Test fun aBlockedChannelForARegisteredSubAppIsAProblem() {
        assertTrue(channelsBlocked(blocked = setOf("video"), registered = listOf("mail", "video")))
    }

    @Test fun aBlockedChannelForNoLongerRegisteredSubAppIsIgnored() {
        // A channel left behind by an uninstalled sub-app must not raise a permanent banner.
        assertFalse(channelsBlocked(blocked = setOf("legacy"), registered = listOf("mail")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PushHealthTest*"`
Expected: FAIL — unresolved `channelsBlocked`.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * Notifications count as blocked when any REGISTERED sub-app's channel is blocked. The
 * old check looked at one hardcoded channel; with a channel per sub-app, a channel left
 * behind by a removed sub-app must not raise a banner forever.
 */
internal fun channelsBlocked(blocked: Set<String>, registered: List<String>): Boolean =
    registered.any { it in blocked }
```

`rememberPushHealth` carries over the existing logic verbatim — the entry/resume
`recheck`, the `forceFresh` re-registration on `WrongServer` (ntfy pins a registration to
the server that was default when it was created), the state-entry `LaunchedEffect`, and
the 30-second `RESUMED`-gated poll — reading blocked-ness through `channelsBlocked` over
`container.registry.ids()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS; `ShellScreen.kt` is now roughly a third of its original size.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/PushHealth.kt \
        app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt \
        app/src/test/java/tech/whitewolf/app/ui/PushHealthTest.kt
git commit -m "refactor(shell): extract rememberPushHealth; per-channel blocked check"
```

---

## Phase 3 — Push routing

### Task 11: Keyed `WakeBus` — one tick for both arms, `pending` deleted

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/push/WakeBus.kt` (rewrite)
- Modify: `app/src/test/java/tech/whitewolf/app/push/WakeBusTest.kt` (rewrite)
- Modify: `app/src/main/java/tech/whitewolf/app/WwtApp.kt` — `wakeBus` type unchanged, keyed API

**Execute this task immediately after Task 4** — before Task 9, which wires a sub-app to
the new `wake` stream. It depends only on Task 1.

**Files (additional):**
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt` — the minimal patch
  that keeps `src/main` compiling; Task 13 replaces it with the generic version

**Interfaces:**
- Consumes: `SubAppId` (Task 1).
- Produces: `class WakeBus` with `fun tick(id: SubAppId): StateFlow<Long>`, `fun signal(id: SubAppId)`; `enum class WakeAction { Foreground, Background }`; `fun wakeAction(appForeground: Boolean, targetIsVisible: Boolean): WakeAction`.

`pending` and `consumePending()` are **deleted**, not made per-sub-app. `pending` was only
consumed on `ON_RESUME`, which never fires when the user reaches a sub-app from the
launcher inside an already-resumed Activity — so a notification posted while the user was
in another sub-app would leave the target stale. One tick for both arms removes the whole
class of bug; sub-apps consume it gated on `RESUMED`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId
import org.junit.Assert.assertEquals
import org.junit.Test

private val mail = SubAppId("mail")
private val video = SubAppId("video")

class WakeBusTest {
    @Test fun tickStartsAtZero() {
        assertEquals(0L, WakeBus().tick(mail).value)
    }

    @Test fun signallingOneSubAppDoesNotDisturbAnother() {
        val bus = WakeBus()
        bus.signal(video)
        assertEquals(1L, bus.tick(video).value)
        assertEquals(0L, bus.tick(mail).value)
    }

    @Test fun theSameFlowInstanceIsReturnedPerSubApp() {
        val bus = WakeBus()
        val first = bus.tick(mail)
        bus.signal(mail)
        assertEquals(1L, first.value)
    }

    @Test fun aWakeThatAlsoNotifiedStillBumpsTheTick() {
        // The old design set `pending` here and left the tick alone, so walking
        // launcher -> mail (no ON_RESUME) showed a stale inbox.
        val bus = WakeBus()
        bus.signal(mail)
        assertEquals(1L, bus.tick(mail).value)
    }

    @Test fun notifyUnlessTheTargetSubAppIsVisible() {
        assertEquals(WakeAction.Foreground, wakeAction(appForeground = true, targetIsVisible = true))
        // App is open, but the user is in a DIFFERENT sub-app: a silent refresh would be
        // invisible and they would never learn mail arrived.
        assertEquals(WakeAction.Background, wakeAction(appForeground = true, targetIsVisible = false))
        assertEquals(WakeAction.Background, wakeAction(appForeground = false, targetIsVisible = false))
        assertEquals(WakeAction.Background, wakeAction(appForeground = false, targetIsVisible = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*WakeBusTest*"`
Expected: FAIL — `tick` takes no argument; `wakeAction` takes one parameter.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.whitewolf.app.subapp.SubAppId
import java.util.concurrent.ConcurrentHashMap

/** What to do with a wake-up. */
enum class WakeAction { Foreground, Background }

/**
 * Refresh silently only when the user is actually looking at the sub-app the wake is
 * for. "App is foreground" is not enough once there are two sub-apps: mail arriving
 * while the user watches a video would refresh an off-screen mailbox and tell them
 * nothing.
 */
fun wakeAction(appForeground: Boolean, targetIsVisible: Boolean): WakeAction =
    if (appForeground && targetIsVisible) WakeAction.Foreground else WakeAction.Background

/**
 * Process-scoped, per-sub-app wake signal. Level-triggered ("something changed, refetch")
 * and data-free.
 *
 * There is deliberately no separate `pending` flag. It was consumed only on ON_RESUME,
 * which does not fire when the user reaches a sub-app from the launcher inside an
 * already-resumed Activity — so a notified wake left the target stale. A StateFlow tick
 * covers both arms: a sub-app that is not composed observes the latest value when it next
 * composes, and consumers gate on RESUMED so nothing refreshes from the background.
 */
class WakeBus {
    private val ticks = ConcurrentHashMap<SubAppId, MutableStateFlow<Long>>()

    private fun flow(id: SubAppId): MutableStateFlow<Long> =
        ticks.computeIfAbsent(id) { MutableStateFlow(0L) }

    fun tick(id: SubAppId): StateFlow<Long> = flow(id)

    /** A wake arrived for [id], whether or not it also raised a notification. */
    fun signal(id: SubAppId) {
        val f = flow(id)
        synchronized(f) { f.value = f.value + 1 }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WakeBusTest*"`
Expected: PASS, 5 tests.

`signalWakeForeground`, `signalWakeBackground`, `consumePending` and the one-argument
`wakeAction` are gone, and `push/PushReceiver.kt:40-47` and `ui/SubAppWebView.kt:66-85`
still call them. `testDebugUnitTest` compiles `src/main`, so this task is **not green**
until they are patched. Apply the minimum now:

```kotlin
// PushReceiver.onMessage — Task 13 replaces this with the registry-driven version.
override fun onMessage(context: Context, message: ByteArray, instance: String) {
    val app = WwtApp.from(context)
    val id = SubAppId("mail")
    app.wakeBus.signal(id)
    // No VisibleRoute yet (Task 12): "app is foreground" is the best available proxy and
    // preserves today's behaviour exactly while there is only one sub-app.
    if (wakeAction(app.isForeground, targetIsVisible = app.isForeground) == WakeAction.Background) {
        Notifications.showNewMail(app)
    }
}
```

and in `SubAppWebView.kt`, replace the `tick`/`consumePending` pair with the keyed tick:

```kotlin
val tick by wakeBus.tick(SubAppId("mail")).collectAsState()
LaunchedEffect(tick, pageLoaded) {
    if (pageLoaded && tick > 0L) webView?.evaluateJavascript(WAKE_JS, null)
}
```

Delete the `ON_RESUME`/`consumePending` `DisposableEffect` entirely — the tick now covers
both arms.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/WakeBus.kt \
        app/src/test/java/tech/whitewolf/app/push/WakeBusTest.kt
git commit -m "feat(push): keyed WakeBus; every wake bumps the tick, pending deleted"
```

---

### Task 12: The process-scoped visible-route holder

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/VisibleRoute.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/WwtApp.kt` — add `val visibleRoute = VisibleRoute()`
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` — publish on route change, clear on stop
- Test: `app/src/test/java/tech/whitewolf/app/push/VisibleRouteTest.kt`

**Interfaces:**
- Consumes: `SubAppId` (Task 1).
- Produces: `class VisibleRoute` with `fun set(id: SubAppId?)`, `fun isVisible(id: SubAppId): Boolean`, `val current: SubAppId?`.

It **cannot** be read from `ShellViewModel`: that is Activity-scoped and `PushReceiver` is
a manifest-registered `BroadcastReceiver` with no Activity. This lives on `WwtApp`
alongside `ForegroundTracker`, and is read from the receiver's background thread — hence
`@Volatile`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleRouteTest {
    @Test fun nothingIsVisibleBeforeAnySubAppOpens() {
        val v = VisibleRoute()
        assertNull(v.current)
        assertFalse(v.isVisible(SubAppId("mail")))
    }

    @Test fun onlyTheOpenSubAppIsVisible() {
        val v = VisibleRoute()
        v.set(SubAppId("video"))
        assertTrue(v.isVisible(SubAppId("video")))
        assertFalse(v.isVisible(SubAppId("mail")))
    }

    @Test fun theLauncherMakesNothingVisible() {
        // At the launcher a wake for any sub-app should notify, not refresh silently.
        val v = VisibleRoute()
        v.set(SubAppId("mail"))
        v.set(null)
        assertFalse(v.isVisible(SubAppId("mail")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*VisibleRouteTest*"`
Expected: FAIL — unresolved `VisibleRoute`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId

/**
 * Which sub-app is on screen, process-scoped so PushReceiver can read it. The receiver is
 * a manifest-registered BroadcastReceiver with no Activity, so this cannot live in an
 * Activity-scoped ViewModel. Written from the UI thread on route change, read from the
 * receiver's background thread — hence @Volatile, exactly like ForegroundTracker.
 *
 * null means the launcher (or nothing) is showing: a wake for any sub-app then notifies.
 */
class VisibleRoute {
    @Volatile
    var current: SubAppId? = null
        private set

    fun set(id: SubAppId?) { current = id }

    fun isVisible(id: SubAppId): Boolean = current == id
}
```

In `ShellScreen`, publish it and clear on stop:

```kotlin
val visible = remember { WwtApp.from(context).visibleRoute }
// Republish on ON_START as well as on route change. Clearing on ON_STOP without
// restoring on ON_START would leave `current` null after any background -> foreground
// cycle, so every wake for the sub-app actually on screen would notify instead of
// refreshing silently — a regression on today's behaviour.
DisposableEffect(lifecycleOwner, route) {
    val target = (route as? ShellRoute.Open)?.id
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        visible.set(target)
    }
    val obs = LifecycleEventObserver { _, e ->
        when (e) {
            Lifecycle.Event.ON_START -> visible.set(target)
            Lifecycle.Event.ON_STOP -> visible.set(null)
            else -> Unit
        }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs); visible.set(null) }
}
```

Add a fourth test asserting the restore, since this is the case that regressed:

```kotlin
    @Test fun theRouteIsRepublishedAfterBackgrounding() {
        val v = VisibleRoute()
        v.set(SubAppId("mail"))
        v.set(null)                    // ON_STOP
        v.set(SubAppId("mail"))        // ON_START restores it
        assertTrue(v.isVisible(SubAppId("mail")))
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*VisibleRouteTest*"`
Expected: PASS, 4 tests (the three above plus the ON_START restore).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/VisibleRoute.kt \
        app/src/main/java/tech/whitewolf/app/WwtApp.kt \
        app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt \
        app/src/test/java/tech/whitewolf/app/push/VisibleRouteTest.kt
git commit -m "feat(push): process-scoped visible-route holder for wake decisions"
```

---

### Task 13: `MailPush` and generic notification routing

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailPush.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/push/Notifications.kt` — becomes a generic
  channel/post helper; the `"mail"` channel id, ID `1` and "New mail" copy move to `MailPush`
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt` — route by `instance`
- Test: `app/src/test/java/tech/whitewolf/app/subapp/mail/MailPushTest.kt`

**Interfaces:**
- Consumes: `SubAppId`, `WakePayload` (Task 1); `SubAppPush` (Task 2); `DeepLink` (Task 5); `WakeBus`, `VisibleRoute`, `wakeAction` (Tasks 11-12).
- Produces: `class MailPush : SubAppPush`; `object Notifications { fun ensureChannel(context, id, name, description); fun post(context, channelId, notificationId, title, text, tapUri) }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.subapp.mail

import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import tech.whitewolf.app.push.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailPushTest {
    private val push = MailPush()

    @Test fun channelIdIsTheSubAppId() {
        assertEquals("mail", push.channelId)
    }

    @Test fun decodesTheBackendsWakeBody() {
        // The mail backend sends exactly this and nothing else — no item id.
        val p = push.decode("""{"type":"new_mail"}""".toByteArray())
        assertEquals(WakePayload(SubAppId("mail"), null), p)
    }

    @Test fun anUnknownBodyDecodesToNull() {
        assertNull(push.decode("""{"type":"something_else"}""".toByteArray()))
        assertNull(push.decode("not json".toByteArray()))
        assertNull(push.decode(ByteArray(0)))
    }

    @Test fun tapTargetIsMailWithNoItem() {
        // Asserts on the payload, not a rebuilt string: the old shape would have passed
        // even if an item id leaked through. android.net.Uri is stubbed in unit tests
        // (isReturnDefaultValues), so SubAppPush exposes a string form for testability
        // and tapUri() is a thin Uri.parse over it.
        assertEquals("wwt://subapp/mail", push.tapTarget(WakePayload(SubAppId("mail"))))
        assertEquals(
            WakePayload(SubAppId("mail"), null),
            DeepLink.parseString(push.tapTarget(WakePayload(SubAppId("mail")))),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MailPushTest*"`
Expected: FAIL — unresolved `MailPush`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.subapp.mail

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.whitewolf.app.push.DeepLink
import tech.whitewolf.app.push.Notifications
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.SubAppPush
import tech.whitewolf.app.subapp.WakePayload

/**
 * Mail's push behaviour. Owns its wire format, its channel and its notification id, so
 * PushReceiver stays generic and never learns any sub-app's payload shape.
 *
 * One collapsing notification: mail says "you have mail", not "you have these mails", so
 * a single stable id is right. A sub-app that wants several to stack (video) derives its
 * id from the item instead.
 */
class MailPush : SubAppPush {
    private val id = SubAppId("mail")
    override val channelId = id.value
    override val channelName = "Mail"
    override val channelDescription = "New mail notifications"

    private val notificationId = 1
    private val json = Json { ignoreUnknownKeys = true }

    override fun decode(body: ByteArray): WakePayload? = try {
        val type = json.parseToJsonElement(body.decodeToString())
            .jsonObject["type"]?.jsonPrimitive?.content
        if (type == "new_mail") WakePayload(id, null) else null
    } catch (e: Exception) {
        null
    }

    override fun notify(context: Context, payload: WakePayload) {
        Notifications.ensureChannel(context, channelId, channelName, channelDescription)
        Notifications.post(
            context = context,
            channelId = channelId,
            notificationId = notificationId,
            title = "New mail",
            text = "You have new mail in WWT",
            tapUri = tapUri(payload),
        )
    }

    override fun tapTarget(payload: WakePayload): String =
        DeepLink.buildString(WakePayload(id, null))
}
```

`Notifications.kt` keeps the permission check and the `ic_notification_dot` icon exactly
as they are, but takes channel and id as parameters, and builds its `PendingIntent` with
the tap URI as intent **data** and a request code derived from the URI:

```kotlin
val intent = Intent(context, MainActivity::class.java)
    .setData(tapUri)
    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
val tap = PendingIntent.getActivity(
    context, tapUri.hashCode(), intent,
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
```

**On enrichment.** `notify()` owning its own notification ids is also what makes the
spec's post-then-replace rule possible: a sub-app posts generic copy immediately and calls
`notify()` again with the same id once an enrichment fetch returns, and the second post
replaces the first. Mail has nothing to enrich — its notification is deliberately generic
— so nothing here does it yet; project B's video sub-app is the first caller. The
constraint the interface must preserve is that `notify()` is safe to call more than once
for one payload.

`PushReceiver.onMessage` becomes generic:

```kotlin
override fun onMessage(context: Context, message: ByteArray, instance: String) {
    val app = WwtApp.from(context)
    val id = SubAppId.parse(instance) ?: return
    val entry = app.container.registry.byId(id) ?: return   // unknown -> ignore, never crash
    val payload = entry.push?.decode(message) ?: return
    // Always bump the tick: the target refreshes whenever the user next looks at it.
    app.wakeBus.signal(id)
    val visible = app.visibleRoute.isVisible(id)
    if (wakeAction(app.isForeground, visible) == WakeAction.Background) {
        entry.push.notify(app, payload)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp/mail/MailPush.kt \
        app/src/main/java/tech/whitewolf/app/push/ \
        app/src/test/java/tech/whitewolf/app/subapp/mail/MailPushTest.kt
git commit -m "feat(push): per-sub-app channels, payload decode, and generic wake routing"
```

---

### Task 14: One UnifiedPush instance per sub-app

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushManager.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushEndpointStore.kt` — key per instance
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushApiClient.kt` — one per sub-app
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt` — `onNewEndpoint` routes by instance
- Test: `app/src/test/java/tech/whitewolf/app/push/PushEndpointStoreTest.kt` (extend)

**Interfaces:**
- Consumes: `SubAppId` (Task 1), `SubAppRegistry` (Task 2).
- Produces: `class PushManager(context: Context, ids: () -> List<SubAppId>)` with `enable()`, `disable()`, `reregister()`, `hasDistributor()`; `PushEndpointStore` gains `save(id, endpoint)`, `get(id)`, `clear(id)`, `all(ids: List<SubAppId>): Map<SubAppId, String>`, and keeps `legacyGet()` for Task 20.

`PushManager`'s constructor gains a parameter, so **`ui/PushHealth.kt` (Task 10) must be
updated in this task too** — it is the only caller that constructs one.

The three existing `PushEndpointStoreTest` cases call the no-arg `save`/`get`/`clear` and
must be **rewritten**, not merely extended. Reuse that file's existing `FakeStore` rather
than inventing a `memoryStore()` helper.

Verified against connector 2.5.0: `registerApp(Context, String instance, ArrayList<String>, String)`
and `unregisterApp(Context, String instance)` both exist. Note that `unregisterApp` drops
the saved distributor once the **last** instance goes, so `reregister()` must unregister
every instance and then call `enable()` — which only re-saves the distributor when exactly
one is installed.

- [ ] **Step 1: Write the failing test**

Add to `PushEndpointStoreTest.kt`:

```kotlin
    @Test fun endpointsAreKeptPerSubApp() {
        val store = PushEndpointStore(FakeStore())
        store.save(SubAppId("mail"), "https://ntfy.whitewolf.tech/m1")
        store.save(SubAppId("video"), "https://ntfy.whitewolf.tech/v1")
        assertEquals("https://ntfy.whitewolf.tech/m1", store.get(SubAppId("mail")))
        assertEquals("https://ntfy.whitewolf.tech/v1", store.get(SubAppId("video")))
    }

    @Test fun clearingOneSubAppLeavesTheOther() {
        val store = PushEndpointStore(FakeStore())
        store.save(SubAppId("mail"), "https://ntfy.whitewolf.tech/m1")
        store.save(SubAppId("video"), "https://ntfy.whitewolf.tech/v1")
        store.clear(SubAppId("mail"))
        assertNull(store.get(SubAppId("mail")))
        assertEquals("https://ntfy.whitewolf.tech/v1", store.get(SubAppId("video")))
    }

    @Test fun allReportsEverySavedEndpointForSignOut() {
        val store = PushEndpointStore(FakeStore())
        store.save(SubAppId("mail"), "https://ntfy.whitewolf.tech/m1")
        assertEquals(mapOf(SubAppId("mail") to "https://ntfy.whitewolf.tech/m1"),
                     store.all(listOf(SubAppId("mail"), SubAppId("video"))))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PushEndpointStoreTest*"`
Expected: FAIL — `save` takes one argument.

- [ ] **Step 3: Write the implementation**

`PushEndpointStore` keys become `"push.endpoint.${id.value}"`, and `all(ids)` reads each.
Keep the existing single-key reader as `legacyGet()` — Task 20 needs it.

`PushManager`:

```kotlin
/** Register every sub-app's instance. Safe to call repeatedly. */
fun enable() {
    val distributors = UnifiedPush.getDistributors(context)
    if (distributors.isEmpty()) return
    if (distributors.size == 1) UnifiedPush.saveDistributor(context, distributors.first())
    ids().forEach { UnifiedPush.registerApp(context, it.value) }
}

fun disable() {
    ids().forEach { UnifiedPush.unregisterApp(context, it.value) }
}

/**
 * Drop every registration and register anew. ntfy pins a registration to whichever server
 * was default when it was created, so enable() alone returns the stale endpoint forever.
 * Unregister ALL instances first: the connector drops the saved distributor when the last
 * instance goes, and enable() re-saves it only when exactly one distributor is installed.
 */
fun reregister() {
    disable()
    enable()
}
```

`PushReceiver.onNewEndpoint` routes by instance:

```kotlin
override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
    val app = context.applicationContext
    val id = SubAppId.parse(instance) ?: return
    val pending = goAsync()
    Thread {
        try {
            val wwtApp = WwtApp.from(app)
            wwtApp.container.pushEndpointStore.save(id, endpoint)
            wwtApp.pushStatusBus.set(pushStatusForEndpoint(endpoint, BuildConfig.NTFY_HOST))
            val ok = wwtApp.container.pushClientFor(id)?.register(endpoint) ?: false
            if (!ok) Log.w("PushReceiver", "push endpoint registration failed for $instance")
        } catch (e: Throwable) {
            Log.w("PushReceiver", "push endpoint registration error", e)
        } finally {
            pending.finish()
        }
    }.start()
}
```

`AppContainer.pushClientFor(id)` returns that sub-app's `PushApiClient`, built with that
sub-app's base URL and token supplier — wired properly in Task 18.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: PASS; APK builds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/ \
        app/src/test/java/tech/whitewolf/app/push/PushEndpointStoreTest.kt
git commit -m "feat(push): one UnifiedPush instance per sub-app"
```

---

## Phase 4 — Identity

> **GATE.** Do not start this phase until both external prerequisites in the spec are
> confirmed. If Authelia cannot issue refresh tokens with a lifespan at least as long as
> the backend session they renew, stop and take the spec's stated fallback (no refresh; a
> backend 401 bounces to interactive sign-in) — that removes Tasks 17's refresh path and
> most of Task 18's fencing, and the plan needs revising before you continue.

### Task 15: Retire password login

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/auth/AuthRepository.kt` — delete `LoginReq`, `login()`, and `Authenticator.login`
- Modify: `app/src/main/java/tech/whitewolf/app/auth/LoginViewModel.kt` — delete `onEmail`, `onPassword`, `submit()`, `email`/`password` state, and the `InvalidCredentials` arm in `completeSso` (`:123`)
- Modify: `app/src/main/java/tech/whitewolf/app/ui/LoginScreen.kt` — delete both `OutlinedTextField`s and the `submit` button; the SSO button becomes the primary action
- Modify: `app/src/test/java/tech/whitewolf/app/auth/AuthRepositoryTest.kt`, `LoginViewModelTest.kt`
- Modify: `app/src/androidTest/java/tech/whitewolf/app/ShellFlowTest.kt:14`, `ui/LoginScreenTest.kt`
- Modify: `README.md` — the "Sign-in" section's password bullet

**Interfaces:**
- Consumes: nothing new.
- Produces: `LoginUiState(loading: Boolean, error: String?, loggedIn: Boolean)` — `email` and `password` removed; `interface Authenticator { fun isLoggedIn(): Boolean; fun logout() }`.

`LoginResult.InvalidCredentials` is deleted with the password path. Check for other
references before removing it — `completeSso` maps it today.

- [ ] **Step 1: Write the failing test**

Update `LoginViewModelTest.kt`: delete the `submit()` and `InvalidCredentials` cases, and
add one that pins the surviving contract:

```kotlin
    @Test fun ssoIsTheOnlyWayIn() {
        // Use the fakes already in this file — they are FakeAuth(result) and FakeSso(),
        // not the other way round.
        val vm = LoginViewModel(FakeAuth(LoginResult.Success), FakeSso())
        assertTrue(vm.ssoAvailable)
        // LoginUiState no longer carries credentials at all.
        assertEquals(LoginUiState(loading = false, error = null, loggedIn = false), vm.state.value)
    }
```

`AuthRepositoryTest` has seven cases exercising `login()`. Delete them; `validate()`,
`invalidate()` and `logout()` coverage stays for now. Note that after Task 18
`AuthRepository` is nearly hollow — `BackendSession` owns minting and `IdentityRepository`
owns the credential — so deleting the class outright in Task 18 is reasonable if nothing
but `validate()` remains.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LoginViewModelTest*"`
Expected: FAIL — `LoginUiState` still has `email`/`password`.

- [ ] **Step 3: Write the implementation**

Delete the members listed above. `LoginScreen` keeps its heading, the `notice` slot
(WWT-57's "Your session expired"), the error text, the spinner, and the SSO button —
which becomes a `Button` rather than a `TextButton` now that it is the only action. Keep
the `sso` test tag.

In `README.md`, replace the two sign-in bullets with a single line stating that sign-in is
Authelia SSO only, and note in the override section that pointing at another backend now
also requires an OIDC provider.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebugAndroidTest`
Expected: unit suite PASS; instrumented sources compile with `LoginScreenTest` and
`ShellFlowTest` updated to the `sso` tag.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/tech/whitewolf/app/auth/ \
           app/src/main/java/tech/whitewolf/app/ui/LoginScreen.kt \
           app/src/test/ app/src/androidTest/ README.md
git commit -m "feat(auth)!: retire password login; SSO is the only way in"
```

---

### Task 16: The `Identity` seam and single-flight refresh

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/auth/Identity.kt`
- Create: `app/src/main/java/tech/whitewolf/app/auth/IdentityRepository.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/auth/OidcAuthService.kt` — add `offline_access`, return the whole token response
- Modify: `app/build.gradle.kts` — no change (AppAuth already present)
- Test: `app/src/test/java/tech/whitewolf/app/auth/IdentityRepositoryTest.kt`

**Interfaces:**
- Consumes: `SecureStore` (existing).
- Produces: `sealed interface RefreshFailure { data object CredentialDead; data object Unreachable }`; `class RefreshException(failure: RefreshFailure)`; `interface Identity { suspend fun freshIdToken(force: Boolean): Result<String>; fun serialize(): String?; fun hasCredential(): Boolean; fun install(serializedOrFresh: Any); fun clear() }`; `class IdentityRepository(identity: Identity, store: SecureStore)` with `suspend fun idToken(force: Boolean = false): Result<String>`, `val generation: StateFlow<Long>`, `fun hasCredential(): Boolean`, `fun install(authState: Any)`, `fun replaceForTest(identity: Identity)`, `fun invalidateRoot()`, `fun clear()`.

**Also modify** `app/src/main/java/tech/whitewolf/app/auth/SsoLogin.kt`: `OidcSsoLogin.signIn`
currently does `auth.loginWithSso(oidc.completeAuthorization(resultData))` and expects a
`String`. `completeAuthorization` now returns the whole `TokenResponse`, so nothing type-checks
until this is rewritten — the sign-in path is otherwise silently left broken:

```kotlin
override suspend fun signIn(resultData: Intent): LoginResult {
    val (authResp, tokenResp) = oidc.completeAuthorization(resultData)
    identity.install(AuthState(authResp, null).apply { update(tokenResp, null) })
    sessionBus.signedIn()
    return LoginResult.Success
}
```

`Identity.clear()` exists because `IdentityRepository.clear()` removing the persisted key is
**not** enough: `AuthStateIdentity` still holds the live `AuthState` in memory, refresh token
and all, so an in-flight 401 arriving after sign-out could still refresh successfully and mint
a fresh session. Clearing the persisted copy without clearing the in-memory one reopens exactly
the hole the generation counter was added to close.

Two rules this task exists to enforce:

**Single-flight.** One `AuthState` per process and one refresh in flight. With rotating
refresh tokens, two concurrent refreshes mean the second gets `invalid_grant` and signs the
user out moments after the first succeeded.

**Persist before use.** A rotated refresh token must be written to `SecureStore` before the
new ID token is handed out. A crash in between orphans the chain and the next refresh fails
`invalid_grant` — an unrecoverable sign-out from one badly-timed process kill.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class FakeIdentity(
    private val result: () -> Result<String>,
    val gate: CompletableDeferred<Unit>? = null,
) : Identity {
    val calls = AtomicInteger(0)
    var serialized = "state-0"
    override suspend fun freshIdToken(force: Boolean): Result<String> {
        calls.incrementAndGet()
        gate?.await()
        serialized = "state-${calls.get()}"
        return result()
    }
    override fun serialize(): String? = serialized
}

private fun memStore() = object : SecureStore {
    val m = mutableMapOf<String, String>()
    override fun getString(key: String) = m[key]
    override fun putString(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

class IdentityRepositoryTest {
    @Test fun concurrentCallersShareOneRefresh() = runTest {
        // Two backends 401 at once. A second refresh with a rotating token would earn
        // invalid_grant and sign the user out right after the first succeeded.
        val gate = CompletableDeferred<Unit>()
        val identity = FakeIdentity({ Result.success("id-token") }, gate)
        val repo = IdentityRepository(identity, memStore())
        val a = async { repo.idToken(force = true) }
        val b = async { repo.idToken(force = true) }
        gate.complete(Unit)
        assertEquals("id-token", a.await().getOrNull())
        assertEquals("id-token", b.await().getOrNull())
        assertEquals(1, identity.calls.get())
    }

    @Test fun theRotatedStateIsPersistedBeforeTheTokenIsReturned() = runTest {
        val store = memStore()
        val identity = FakeIdentity({ Result.success("id-token") })
        IdentityRepository(identity, store).idToken(force = true)
        assertEquals("state-1", store.getString("auth.identity"))
    }

    @Test fun aDeadCredentialBumpsTheGenerationAndClearsState() = runTest {
        val store = memStore()
        val identity = FakeIdentity({ Result.failure(RefreshException(RefreshFailure.CredentialDead)) })
        store.putString("auth.identity", "state-0")
        val repo = IdentityRepository(identity, store)
        val before = repo.generation.value
        val r = repo.idToken(force = true)
        assertTrue(r.isFailure)
        assertEquals(before + 1, repo.generation.value)
        assertNull(store.getString("auth.identity"))
    }

    @Test fun anUnreachableIdpDoesNotBumpTheGenerationOrClearState() = runTest {
        // Authelia being down is not the user being signed out.
        val store = memStore()
        store.putString("auth.identity", "state-0")
        val identity = FakeIdentity({ Result.failure(RefreshException(RefreshFailure.Unreachable)) })
        val repo = IdentityRepository(identity, store)
        val before = repo.generation.value
        assertTrue(repo.idToken(force = true).isFailure)
        assertEquals(before, repo.generation.value)
        assertEquals("state-0", store.getString("auth.identity"))
    }

    @Test fun clearBumpsTheGeneration() {
        val repo = IdentityRepository(FakeIdentity({ Result.success("t") }), memStore())
        val before = repo.generation.value
        repo.clear()
        assertEquals(before + 1, repo.generation.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*IdentityRepositoryTest*"`
Expected: FAIL — unresolved `Identity`, `IdentityRepository`, `RefreshFailure`.

- [ ] **Step 3: Write the implementation**

`Identity.kt`:

```kotlin
package tech.whitewolf.app.auth

/** Why a refresh failed — the distinction that decides sign-out vs "try again later". */
sealed interface RefreshFailure {
    /** invalid_grant / invalid_client / unauthorized_client: the suite credential is dead. */
    data object CredentialDead : RefreshFailure
    /** Network, 5xx, IdP unreachable. NOT a sign-out. */
    data object Unreachable : RefreshFailure
}

class RefreshException(val failure: RefreshFailure) : Exception(failure.toString())

/**
 * The OIDC credential, behind a seam so refresh logic is JVM-testable — the same trick
 * SsoLogin already uses to keep AppAuth types out of LoginViewModel.
 */
interface Identity {
    /** A usable ID token, refreshing when [force] or when the current one has expired. */
    suspend fun freshIdToken(force: Boolean): Result<String>

    /** The serialized AuthState to persist, or null when there is none. */
    fun serialize(): String?
}
```

`IdentityRepository.kt`:

```kotlin
package tech.whitewolf.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The root credential: one AuthState per process, one refresh in flight, and a generation
 * counter that fences work started under a credential that has since been replaced.
 *
 * Single-flight matters because refresh tokens rotate: two concurrent refreshes mean the
 * second presents a rotated-out token, earns invalid_grant, and signs the user out
 * immediately after the first succeeded.
 */
class IdentityRepository(
    private var identity: Identity,
    private val store: SecureStore,
) {
    private val key = "auth.identity"

    private val _generation = MutableStateFlow(0L)
    /** Bumped on sign-out and on root invalidation. Work started under an older value is stale. */
    val generation: StateFlow<Long> = _generation

    private var inFlight: CompletableDeferred<Result<String>>? = null

    /**
     * A usable ID token, coalescing concurrent callers onto one refresh.
     *
     * The critical section contains no suspension point. `await()` inside `synchronized`
     * is a hard Kotlin compile error ("The 'await' suspension point is inside a critical
     * section") and would hold a monitor across a suspend besides — so the deferred is
     * taken or created under the lock, and awaited outside it.
     */
    suspend fun idToken(force: Boolean = false): Result<String> {
        var mine: CompletableDeferred<Result<String>>? = null
        val pending = synchronized(this) {
            inFlight ?: CompletableDeferred<Result<String>>().also {
                inFlight = it
                mine = it
            }
        }
        // Not the starter: just wait for whoever is.
        val started = mine ?: return pending.await()

        val result = try {
            identity.freshIdToken(force).onSuccess {
                // Persist the (possibly rotated) state BEFORE handing the token out: a
                // crash in between would orphan the chain and make the next refresh fail
                // invalid_grant, which is an unrecoverable sign-out.
                identity.serialize()?.let { s -> store.putString(key, s) }
            }.onFailure { e ->
                if ((e as? RefreshException)?.failure == RefreshFailure.CredentialDead) clear()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never convert cancellation into a failed result: waiters would be handed a
            // bogus refresh failure and could sign the user out.
            synchronized(this) { inFlight = null }
            started.cancel(e)
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        // Complete BEFORE releasing the slot. Releasing first leaves a window in which a
        // new caller starts a second refresh — which, with rotating tokens, earns
        // invalid_grant and signs the user out moments after this one succeeded.
        started.complete(result)
        synchronized(this) { inFlight = null }
        return result
    }

    fun hasCredential(): Boolean = store.getString(key) != null

    /** A fresh interactive sign-in. Persists immediately and resets the generation fence. */
    fun install(authState: Any) {
        identity.install(authState)
        identity.serialize()?.let { store.putString(key, it) }
    }

    /** Debug-only seam for instrumented tests — see Task 18. */
    fun replaceForTest(replacement: Identity) {
        check(BuildConfig.DEBUG) { "test identity is debug-only" }
        identity = replacement
    }

    /** The server says our credential is dead. */
    fun invalidateRoot() = clear()

    fun clear() {
        // Clear the in-memory credential too, not just the persisted copy — otherwise a
        // late 401 can still refresh against an AuthState we think is gone.
        identity.clear()
        store.remove(key)
        _generation.value = _generation.value + 1
    }
}
```

In `OidcAuthService`, change the scope and stop discarding the token response:

```kotlin
).setScope("openid profile email groups offline_access").build()
```

and return the whole `TokenResponse` from `completeAuthorization` so an `AuthState` can be
built from it, with `AuthStateIdentity` implementing `Identity` over
`AuthState.performActionWithFreshTokens`, mapping `AuthorizationException` codes onto
`RefreshFailure` (`invalid_grant`/`invalid_client`/`unauthorized_client` →
`CredentialDead`, everything else → `Unreachable`) and calling `setNeedsTokenRefresh(true)`
when `force` is set — `getNeedsTokenRefresh()` keys off **access**-token expiry, but what
we replay is the **ID** token, so without this it re-POSTs the same expired token.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*IdentityRepositoryTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/auth/ \
        app/src/test/java/tech/whitewolf/app/auth/IdentityRepositoryTest.kt
git commit -m "feat(auth): Identity seam with single-flight refresh and persist-before-use"
```

---

### Task 17: `BackendSession` — mint on demand, re-mint on 401

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/auth/BackendSession.kt`
- Test: `app/src/test/java/tech/whitewolf/app/auth/BackendSessionTest.kt`

**Interfaces:**
- Consumes: `IdentityRepository`, `RefreshFailure` (Task 16); `TokenStore` (existing, now per-sub-app keys); `SubAppId` (Task 1).
- Produces: `sealed interface MintResult { data class Ok(val token: String); data object Unavailable; data object RootDead }`; `class BackendSession(id: SubAppId, baseUrl: String, http: OkHttpClient, identity: IdentityRepository, store: TokenStore)` with `val token: StateFlow<String?>`, `suspend fun bearer(): String?`, `suspend fun onUnauthorized(): MintResult`, `fun clear()`.

Two rules this task exists to enforce:

**Mint on demand, never on entry.** Push registration needs a bearer, video is reached by
notification, and a notification only arrives if registration succeeded. Minting "when the
user opens the sub-app" deadlocks that loop — a sub-app never manually opened would never
register, never notify, and never be opened.

**One retry, never two.** A re-mint that itself 401s is terminal for that sub-app.

**It does not seed cookies.** That would make identity know mail is web-hosted. It
publishes `token` as a `StateFlow` and the web sub-app seeds its own (Task 19).

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tech.whitewolf.app.subapp.SubAppId

class BackendSessionTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun session(identity: IdentityRepository) = BackendSession(
        id = SubAppId("mail"),
        baseUrl = server.url("/").toString().trimEnd('/'),
        http = OkHttpClient(),
        identity = identity,
        store = freshTokenStore(),
    )

    private fun okMint(token: String) = MockResponse()
        .setResponseCode(200)
        .setBody("""{"ok":true,"token":"$token","expires":9999999999}""")

    @Test fun aBearerIsAvailableWithoutTheSubAppEverBeingOpened() = runTest {
        // Push registration must work for a sub-app the user has never entered.
        server.enqueue(okMint("t1"))
        val s = session(identityReturning("id-token"))
        assertEquals("t1", s.bearer())
    }

    @Test fun theTokenIsPublishedSoAWebSubAppCanReseedAndReload() = runTest {
        server.enqueue(okMint("t1"))
        val s = session(identityReturning("id-token"))
        assertNull(s.token.value)
        s.bearer()
        assertEquals("t1", s.token.value)
    }

    @Test fun aSecondCallReusesTheMintedSession() = runTest {
        server.enqueue(okMint("t1"))
        val s = session(identityReturning("id-token"))
        s.bearer()
        assertEquals("t1", s.bearer())
        assertEquals(1, server.requestCount)
    }

    @Test fun a401ReMintsOnceAndSucceeds() = runTest {
        server.enqueue(okMint("t2"))
        val s = session(identityReturning("id-token"))
        assertEquals(MintResult.Ok("t2"), s.onUnauthorized())
    }

    @Test fun aReMintThatItselfGets401IsTerminalNotALoop() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val identity = identityReturning("id-token")
        val s = session(identity)
        assertEquals(MintResult.Unavailable, s.onUnauthorized())
        assertEquals(1, server.requestCount)   // exactly one attempt
        assertEquals(1, identity.calls.get())  // and exactly one refresh, not two
    }

    @Test fun twoConcurrentCallersMintOnce() = runTest {
        // Push registration and validate-on-entry can both find no token at once.
        server.enqueue(okMint("t1"))
        val s = session(identityReturning("id-token"))
        val a = async { s.bearer() }
        val b = async { s.bearer() }
        assertEquals("t1", a.await())
        assertEquals("t1", b.await())
        assertEquals(1, server.requestCount)
    }

    @Test fun a403MeansNoAccountOnThisServiceNotASignOut() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val s = session(identityReturning("id-token"))
        assertEquals(MintResult.Unavailable, s.onUnauthorized())
    }

    @Test fun anIdpOutageLeavesTheSessionAloneRatherThanSigningOut() = runTest {
        val s = session(identityFailing(RefreshFailure.Unreachable))
        assertEquals(MintResult.Unavailable, s.onUnauthorized())
        assertEquals(0, server.requestCount)
    }

    @Test fun aDeadCredentialIsRootInvalidation() = runTest {
        val s = session(identityFailing(RefreshFailure.CredentialDead))
        assertEquals(MintResult.RootDead, s.onUnauthorized())
    }

    @Test fun aMintFinishingAfterSignOutIsDiscarded() = runTest {
        // Sign-out races an in-flight push registration. Without the generation fence this
        // would leave a live bearer on the device with the login screen showing.
        server.enqueue(okMint("t1"))
        val identity = identityReturning("id-token")
        val s = session(identity)
        identity.clear()                 // sign-out bumps the generation mid-flight
        assertNull(s.bearerFencedAt(generation = 0L))
        assertNull(s.token.value)
    }
}
```

Write `identityReturning` / `identityFailing` / `freshTokenStore` as small local helpers
in the test file, following the `FakeIdentity` and `memStore` shapes from Task 16.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackendSessionTest*"`
Expected: FAIL — unresolved `BackendSession`, `MintResult`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tech.whitewolf.app.subapp.SubAppId
import java.io.IOException

sealed interface MintResult {
    data class Ok(val token: String) : MintResult
    /** This service is unusable right now; the identity is fine. Never a sign-out. */
    data object Unavailable : MintResult
    /** The suite credential is dead. The caller signs the whole suite out. */
    data object RootDead : MintResult
}

/**
 * One backend's session: an opaque bearer minted from an Authelia ID token via that
 * backend's /api/auth/native.
 *
 * Minting is on demand rather than on sub-app entry. Entry-minting deadlocks push —
 * registration needs a bearer, a notification-only sub-app is reached by notification, and
 * the notification requires registration to have succeeded.
 *
 * It publishes [token] rather than seeding cookies itself: identity must not know that
 * mail is web-hosted. A web sub-app observes this and re-seeds and reloads.
 */
class BackendSession(
    private val id: SubAppId,
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val identity: IdentityRepository,
    private val store: TokenStore,
    /** True while a deliberate sign-out is tearing down; blocks new mints. */
    private val signingOut: () -> Boolean = { false },
) {
    /**
     * Only a re-mint after a rejected bearer forces a refresh. A first mint must not:
     * with rotating refresh tokens, forcing on every cold start burns a rotation for
     * nothing, and it makes the very first mint after interactive sign-in depend on the
     * refresh grant working rather than the token we already hold.
     */
    private var forceRefresh = false

    @Serializable private data class NativeReq(@SerialName("id_token") val idToken: String)
    @Serializable private data class MintResp(
        val ok: Boolean = false, val token: String = "", val expires: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()
    private val mintLock = Mutex()

    private val _token = MutableStateFlow(store.token())
    /** The live bearer. Web sub-apps re-seed their cookie and reload when this changes. */
    val token: StateFlow<String?> = _token

    /** The bearer, minting it if we do not have one. Null when unavailable. */
    suspend fun bearer(): String? = bearerFencedAt(identity.generation.value)

    /** As [bearer], but a result arriving after the generation moved on is discarded. */
    suspend fun bearerFencedAt(generation: Long): String? {
        store.token()?.let { return it }
        return when (val r = mint(generation)) {
            is MintResult.Ok -> r.token
            else -> null
        }
    }

    /** The backend rejected our bearer. Re-mint exactly once, forcing a refresh. */
    suspend fun onUnauthorized(): MintResult {
        val rejected = store.token()
        // Another caller may already have re-minted while this 401 was in flight.
        store.token()?.let { if (it != rejected) return MintResult.Ok(it) }
        store.clear()
        _token.value = null
        forceRefresh = true
        return try { mint(identity.generation.value) } finally { forceRefresh = false }
    }

    private suspend fun mint(generation: Long): MintResult = mintLock.withLock {
        // Re-check inside the lock. Without this the mutex only SERIALISES two callers
        // (push registration and validate-on-entry both seeing a null token) — they mint
        // one after the other, producing two refreshes and two backend sessions.
        store.token()?.let { return@withLock MintResult.Ok(it) }

        // A deliberate sign-out in progress must not be handed a fresh session.
        if (signingOut()) return@withLock MintResult.Unavailable

        val idTokenResult = identity.idToken(force = forceRefresh)
        val idToken = idTokenResult.getOrElse { e ->
            return when ((e as? RefreshException)?.failure) {
                RefreshFailure.CredentialDead -> MintResult.RootDead
                else -> MintResult.Unavailable   // IdP unreachable is not a sign-out
            }
        }

        val body = json.encodeToString(NativeReq.serializer(), NativeReq(idToken))
            .toRequestBody(media)
        val req = Request.Builder().url("$baseUrl/api/auth/native").post(body).build()
        // OkHttp blocks; mint() is called from a receiver thread AND from composition.
        val parsed = try {
            withContext(Dispatchers.IO) { http.newCall(req).execute() }.use { resp ->
                val text = resp.body?.string().orEmpty()
                // 401: the backend cannot verify a FRESH token (JWKS, audience).
                // 403: valid identity, no account on this service.
                // Neither is a sign-out, and neither is retried — one attempt, terminal.
                if (!resp.isSuccessful) return MintResult.Unavailable
                json.decodeFromString(MintResp.serializer(), text)
            }
        } catch (e: IOException) {
            return MintResult.Unavailable
        } catch (e: kotlinx.serialization.SerializationException) {
            return MintResult.Unavailable
        }

        if (!parsed.ok || parsed.token.isBlank()) return MintResult.Unavailable

        // Sign-out may have run while we were on the wire. Publishing now would leave a
        // live multi-day bearer on a device showing the login screen.
        if (identity.generation.value != generation) return MintResult.Unavailable

        store.save(parsed.token, parsed.expires)
        _token.value = parsed.token
        return MintResult.Ok(parsed.token)
    }

    fun clear() {
        store.clear()
        _token.value = null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackendSessionTest*"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/auth/BackendSession.kt \
        app/src/test/java/tech/whitewolf/app/auth/BackendSessionTest.kt
git commit -m "feat(auth): per-backend sessions minted on demand, fenced by generation"
```

---

### Task 18: Wire `AppContainer` for many backends and fence sign-out

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/AppContainer.kt` — delete the single `baseUrl`
- Modify: `app/src/main/java/tech/whitewolf/app/auth/AuthRepository.kt` — `validate()` per sub-app; `forget()` spans backends
- Modify: `app/src/main/java/tech/whitewolf/app/auth/SessionBus.kt` — `beginSignOut` vetoes mints
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` — sign-out teardown loop
- Modify: `app/build.gradle.kts` — no new field yet (mail only until project B)
- Test: `app/src/test/java/tech/whitewolf/app/auth/SignOutTest.kt`

**Interfaces:**
- Consumes: `IdentityRepository` (Task 16), `BackendSession` (Task 17), `SubAppRegistry` (Task 2), `PushEndpointStore`, `PushApiClient` (Task 14).
- Produces: `AppContainer.registry: SubAppRegistry`, `AppContainer.sessionFor(id): BackendSession?`, `AppContainer.pushClientFor(id): PushApiClient?`, `AppContainer.signOut(): Unit` (the whole ordered teardown, moved out of `ShellScreen`).

`SessionBus.loggedIn` stops meaning "mail's token exists" (`AppContainer.kt:27`) and starts
meaning "we hold a usable identity".

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutTest {
    @Test fun pushIsUnregisteredBeforeTokensAreCleared() = runTest {
        // Unregister needs the live bearer, so ordering is load-bearing.
        val order = mutableListOf<String>()
        val teardown = SignOutTeardown(
            unregisterPush = { order += "unregister" },
            clearSessions = { order += "clear" },
            clearIdentity = { order += "identity" },
            discardScopes = { order += "scopes" },
        )
        teardown.run()
        assertEquals(listOf("unregister", "clear", "identity", "scopes"), order)
    }

    @Test fun theGateIsLoweredEvenWhenTeardownThrows() = runTest {
        // A gate left raised would mute every real "session expired" notice thereafter.
        val bus = SessionBus(true)
        val teardown = SignOutTeardown(
            unregisterPush = { throw IllegalStateException("EncryptedSharedPreferences blew up") },
            clearSessions = {}, clearIdentity = {}, discardScopes = {},
            session = bus,
        )
        runCatching { teardown.run() }
        bus.invalidate()
        assertTrue(bus.invalidated.value)   // gate is down again, so a real 401 speaks up
    }

    @Test fun a401DuringOurOwnTeardownShowsNoExpiryNotice() = runTest {
        val bus = SessionBus(true)
        bus.beginSignOut()
        bus.invalidate()
        assertFalse(bus.invalidated.value)
        bus.endSignOut()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SignOutTest*"`
Expected: FAIL — unresolved `SignOutTeardown`.

- [ ] **Step 3: Write the implementation**

Extract the teardown from `ShellScreen.kt:178-201` into a testable class:

```kotlin
package tech.whitewolf.app.auth

/**
 * The ordered multi-backend sign-out. Order is load-bearing: unregistering push uses each
 * sub-app's live bearer, so it must happen before the sessions are cleared.
 *
 * The gate spans the WHOLE teardown and is lowered in a finally. A 401 from the second
 * backend's unregister must not surface "your session expired" on a sign-out the user
 * asked for — and a gate left raised would mute every real expiry notice afterwards.
 */
class SignOutTeardown(
    private val unregisterPush: () -> Unit,
    private val clearSessions: () -> Unit,
    private val clearIdentity: () -> Unit,
    private val discardScopes: () -> Unit,
    private val session: SessionBus? = null,
) {
    fun run() {
        session?.beginSignOut()
        try {
            unregisterPush()
            clearSessions()
            clearIdentity()
            discardScopes()
        } finally {
            session?.endSignOut()
        }
    }
}
```

`AppContainer` becomes:

```kotlin
class AppContainer(context: Context) {
    private val http = OkHttpClient()
    private val secureStore = EncryptedPrefsStore(context.applicationContext)

    val identity = IdentityRepository(AuthStateIdentity(context, secureStore), secureStore)

    // "Signed in" now means "we hold a usable identity". Deriving it from the persisted
    // credential is what makes a cold start land on the shell rather than the login
    // screen — a literal `false` here ships an app that asks you to sign in every launch.
    val sessionBus = SessionBus(initial = identity.hasCredential())

    val pushEndpointStore = PushEndpointStore(secureStore)

    // NOTE: SubAppScopes is deliberately NOT here. It holds WebViews built with an
    // Activity context; a process-scoped copy would leak the Activity across rotation and
    // let discardAll() touch WebViews off the UI thread. It lives in `remember` at the
    // ShellScreen root (spec section 2) and is passed into signOut().

    private val sessions = mutableMapOf<SubAppId, BackendSession>()
    private val pushClients = mutableMapOf<SubAppId, PushApiClient>()

    // One entry per sub-app, each carrying its OWN base URL. There is no shared baseUrl.
    val registry: SubAppRegistry = buildRegistry(context)

    fun sessionFor(id: SubAppId): BackendSession? = sessions[id]
    fun pushClientFor(id: SubAppId): PushApiClient? = pushClients[id]

    private fun buildRegistry(context: Context): SubAppRegistry {
        val mailId = SubAppId("mail")
        val mailSession = BackendSession(
            mailId, BuildConfig.MAIL_BASE_URL, http, identity,
            TokenStore(secureStore, keyPrefix = "auth.${mailId.value}"),
        )
        sessions[mailId] = mailSession
        pushClients[mailId] = PushApiClient(
            http, BuildConfig.MAIL_BASE_URL,
            token = { runBlocking { mailSession.bearer() } },
            onUnauthorized = { runBlocking { handleUnauthorized(mailId) } },
        )
        return SubAppRegistry(listOf(SubAppEntry(
            ui = MailSubApp(BuildConfig.MAIL_BASE_URL, scopes, { mailSession.token.value }, ::isOnline),
            push = MailPush(),
        )))
    }

    /** A backend rejected our bearer: re-mint, and sign the suite out only if the root is dead. */
    private suspend fun handleUnauthorized(id: SubAppId) {
        when (sessions[id]?.onUnauthorized()) {
            is MintResult.RootDead -> sessionBus.invalidate()
            else -> Unit   // per-sub-app: that service is unavailable, session intact
        }
    }

    /**
     * Blocking: unregister is an OkHttp call. Callers run it on a background thread, as
     * ui/ShellScreen.kt:184 does today. [discardScopes] is supplied by the UI (the scopes
     * are Activity-scoped) and MUST be posted to the main thread — it destroys WebViews.
     */
    fun signOut(discardScopes: () -> Unit) = SignOutTeardown(
        unregisterPush = {
            pushEndpointStore.all(registry.ids()).forEach { (id, ep) ->
                pushClientFor(id)?.unregister(ep)
                pushEndpointStore.clear(id)
            }
        },
        clearSessions = { sessions.values.forEach { it.clear() } },
        clearIdentity = { identity.revokeAndClear() },
        discardScopes = discardScopes,
        session = sessionBus,
    ).run().also { sessionBus.signedOut() }
}
```

`ShellScreen` owns the scopes and keeps sign-out off the main thread:

```kotlin
val scopes = remember { SubAppScopes() }
DisposableEffect(Unit) { onDispose { scopes.discardAll() } }

val signOut = {
    container.sessionBus.signedOut()          // flip the UI now
    Thread {
        container.signOut(discardScopes = { mainHandler.post { scopes.discardAll() } })
    }.start()
}
```

`TokenStore` gains a `keyPrefix` constructor parameter so its keys become
`auth.mail.token` / `auth.mail.expires`; default it to `"auth"` so existing tests keep
compiling, and update `TokenStoreTest` with one case asserting two prefixes do not collide.

`AuthRepository.validate()` becomes per-sub-app: `GET {baseUrl}/api/me` with that
sub-app's bearer, called on sub-app entry rather than on every resume. At launch, call
`identity.idToken(force = true)` once as a keep-alive: a `CredentialDead` failure signs
out, anything else is ignored.

**Revoke the refresh token on sign-out.** It is now a long-lived suite-wide credential, so
local teardown alone leaves it valid in Authelia's store. Add to `AuthStateIdentity`:

AppAuth 0.11.1 has no typed accessor for the revocation endpoint, so read it out of the
raw discovery JSON. The teardown lambdas are plain `() -> Unit` and already run on a
background thread, so this is a blocking function, not a `suspend` one:

```kotlin
/** Best effort: POST the refresh token to the provider's revocation_endpoint. Never
 *  blocks sign-out — a failure here is logged, not surfaced. Called by revokeAndClear(),
 *  which then clears in-memory and persisted state. */
fun revoke() {
    val endpoint = authState.authorizationServiceConfiguration
        ?.discoveryDoc?.docJson?.optString("revocation_endpoint")
        ?.takeIf { it.isNotEmpty() } ?: return
    val token = authState.refreshToken ?: return
    runCatching {
        http.newCall(
            Request.Builder().url(endpoint).post(
                FormBody.Builder()
                    .add("token", token)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", BuildConfig.OIDC_CLIENT_ID)
                    .build(),
            ).build(),
        ).execute().close()
    }.onFailure { Log.w("Identity", "refresh-token revocation failed", it) }
}
```

Expose it as `IdentityRepository.revokeAndClear()` — revoke, then `clear()` — and call
that from the teardown's `clearIdentity`. Where the provider publishes no
`revocation_endpoint` this is a no-op: the spec's "where the provider exposes one" caveat,
not a silent failure.

One consequence to state for the implementer: `PushApiClient.onUnauthorized` re-mints but
does **not** retry the registration that 401'd. The endpoint is re-registered on the next
`enable()` (entry and resume), so this is acceptable — but it is a deliberate choice, not
an oversight.

**Expose a test-only identity seam.** Instrumented tests could previously seed a bearer
directly; a usable session now also needs an identity, and without a seam every signed-in
device test would need a live interactive OIDC flow.

The seam must swap the `Identity` **inside** the single `IdentityRepository`, not replace
the repository: every `BackendSession` captured the original instance at construction, so
reassigning an `AppContainer.identity` field changes nothing they use. That is why Task 16
adds `IdentityRepository.replaceForTest`.

```kotlin
/** Test-only. The guard is a compile-time constant, so this call is impossible to
 *  reach in a release build — a production caller would be an authentication bypass. */
@VisibleForTesting
fun installTestIdentity(replacement: Identity) = identity.replaceForTest(replacement)
```

Do **not** try to unit-test the `BuildConfig.DEBUG` guard: it is a per-variant compile-time
constant, so `testDebugUnitTest` can never observe it false, and `AppContainer` needs a real
`Context` for `EncryptedPrefsStore` and is not JVM-constructible anyway.

**What the fake must do.** A canned `Identity` returning an invented ID token gets a 401
from the real backend (`internal/oidc/native.go` verifies signature and audience), so the
mint fails and mail never loads. The working recipe, for Task 9's `ShellNavTest` and any
later signed-in device test:

1. Write a real 7-day mail session token into `auth.mail.token` / `auth.mail.expires`, and
   any non-empty marker into `auth.identity` so `hasCredential()` is true. (Obtain the
   token by hand and check it against `/api/me` before spending device minutes — it
   expires in 7 days.)
2. `installTestIdentity` with a fake whose `freshIdToken` returns
   `Result.failure(RefreshException(RefreshFailure.Unreachable))`, so nothing ever contacts
   Authelia and no failure is mistaken for a dead credential.
3. Seed **before the first composition**. `createAndroidComposeRule<MainActivity>()` launches
   the Activity before `@Before` runs, so use `createEmptyComposeRule()` and
   `ActivityScenario.launch(MainActivity::class.java)` after seeding.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: PASS; APK builds; no reference to a shared `baseUrl` remains
(`grep -rn "val baseUrl" app/src/main` returns nothing).

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/tech/whitewolf/app/ app/src/test/java/tech/whitewolf/app/
git commit -m "feat(auth): per-sub-app sessions in AppContainer; ordered fenced sign-out"
```

---

### Task 19: Mail reloads when its session is re-minted

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailSubApp.kt` — take `BackendSession`
- Modify: `app/src/main/java/tech/whitewolf/app/subapp/mail/MailContent.kt` — observe the token
- Test: `app/src/test/java/tech/whitewolf/app/subapp/mail/MailReloadTest.kt`

**Interfaces:**
- Consumes: `BackendSession.token` (Task 17), `MailWebSession` (Task 3).
- Produces: `internal fun reloadNeeded(seeded: String?, current: String?): Boolean`.

**Why this task exists.** The mail SPA probes `/api/me` on load and renders **its own**
login form when that fails (`email-client-maileroo/web/src/App.tsx:78,127`); it never
re-probes when the cookie changes. Without this, "cold start with a revoked token → SPA
shows its login card → shell 401s → silent re-mint → cookie fixed" ends with the user
staring at a login form inside a shell that thinks it is signed in — strictly worse than
today, which bounces to the native login and rebuilds the WebView on re-login.

`wwtWake()` is **not** sufficient: once the SPA has flipped to its unauthenticated view
there is no mail list to refresh. This must be a `loadUrl`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.subapp.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailReloadTest {
    @Test fun theFirstTokenTriggersTheInitialLoad() {
        assertTrue(reloadNeeded(seeded = null, current = "t1"))
    }

    @Test fun anUnchangedTokenDoesNotReload() {
        // Every recomposition must not reload the SPA.
        assertFalse(reloadNeeded(seeded = "t1", current = "t1"))
    }

    @Test fun aReMintedTokenReloadsSoTheSpaLeavesItsOwnLoginForm() {
        assertTrue(reloadNeeded(seeded = "t1", current = "t2"))
    }

    @Test fun losingTheTokenDoesNotReloadIntoAnUnauthenticatedPage() {
        // Sign-out tears the whole sub-app down; do not race it with a load.
        assertFalse(reloadNeeded(seeded = "t1", current = null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MailReloadTest*"`
Expected: FAIL — unresolved `reloadNeeded`.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * Whether the WebView must be (re)loaded. The SPA renders its own login form when
 * /api/me fails and never re-probes on a cookie change, so a silent re-mint has to be
 * followed by a real load — a wwtWake() would refresh a mail list that is not on screen.
 */
internal fun reloadNeeded(seeded: String?, current: String?): Boolean =
    current != null && current != seeded
```

In `MailContent`:

`MailSubApp`'s third parameter changes from `token: () -> String?` to
`tokenFlow: StateFlow<String?>`, passed straight through to `MailContent`; update the
`AppContainer` call site from `{ mailSession.token.value }` to `mailSession.token`.
`MailContent` also gains a `cookies: WebCookies` parameter — it seeds its own cookie now
that `BackendSession` does not.

**The seeded token lives on the session, not in composition.** `remember` resets on every
launcher round-trip, so a composition-local copy would be null on re-entry, `reloadNeeded`
would fire, and the SPA would reload every single time — defeating Tasks 3, 4 and 8 and
the spec goal "switching sub-apps does not reload the mail SPA". `MailWebSession.seededToken`
(Task 3) is retained alongside the WebView, which is the only scope that survives.

```kotlin
val token by tokenFlow.collectAsState()
LaunchedEffect(token) {
    if (reloadNeeded(session.seededToken, token)) {
        cookies.seed(url, sessionCookieLine(token!!))
        session.seededToken = token
        session.web.loadUrl(url)
    }
}
```

The first load is therefore gated on a real session rather
than racing it — delete the "seed cookie then loadUrl in the setCookie callback" branch
from the WebView factory, since this effect now owns loading.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: PASS; APK builds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp/mail/ \
        app/src/test/java/tech/whitewolf/app/subapp/mail/MailReloadTest.kt
git commit -m "fix(mail): reload the WebView when the session is re-minted"
```

---

## Phase 5 — Migration and documentation

### Task 20: Retire the legacy push endpoint on first run

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/PushMigration.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/AppContainer.kt` — run it once at startup
- Test: `app/src/test/java/tech/whitewolf/app/push/PushMigrationTest.kt`

**Interfaces:**
- Consumes: `PushEndpointStore.legacyGet()` (Task 14), `SecureStore` (existing).
- Produces: `class PushMigration(store: SecureStore, unregister: (bearer: String, endpoint: String) -> Boolean, unregisterDefaultInstance: () -> Unit)` with `fun runOnce(): Boolean`.

It reads the legacy keys straight off `SecureStore`, so it needs no `PushEndpointStore`.
Wire `unregister` as
`PushApiClient(http, BuildConfig.MAIL_BASE_URL, token = { bearer }).unregister(endpoint)`
— a throwaway client bound to the legacy bearer, since the real per-sub-app clients now
resolve their token through `BackendSession`.

**Ordering is the whole point.** Unregistering the old endpoint needs the legacy bearer,
and the backend only prunes an endpoint when the push server returns 404/410 — which ntfy
does not do for an unsubscribed topic. Clearing the bearer first would strand a dead row
in the registry forever.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMigrationTest {
    private fun store(vararg pairs: Pair<String, String>) = object : tech.whitewolf.app.auth.SecureStore {
        val m = mutableMapOf(*pairs)
        override fun getString(key: String) = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    @Test fun unregistersWithTheLegacyBearerBeforeClearingIt() {
        val order = mutableListOf<String>()
        val s = store("auth.token" to "legacy", "auth.expires" to "9999999999",
                      "push.endpoint" to "https://ntfy.whitewolf.tech/old")
        val m = PushMigration(
            store = s,
            unregister = { bearer, ep ->
                order += "unregister:$bearer:$ep"; true
            },
            unregisterDefaultInstance = { order += "connector" },
        )
        assertTrue(m.runOnce())
        assertEquals(
            listOf("unregister:legacy:https://ntfy.whitewolf.tech/old", "connector"),
            order,
        )
        assertNull(s.getString("auth.token"))
        assertNull(s.getString("push.endpoint"))
    }

    @Test fun aFreshInstallDoesNothing() {
        val m = PushMigration(store(), unregister = { _, _ -> true }, unregisterDefaultInstance = {})
        assertFalse(m.runOnce())
    }

    @Test fun theLegacyKeysAreClearedEvenIfTheUnregisterCallFails() {
        // An offline first run must not leave the user permanently on the old credential.
        val s = store("auth.token" to "legacy", "push.endpoint" to "https://x/old")
        val m = PushMigration(s, unregister = { _, _ -> false }, unregisterDefaultInstance = {})
        assertTrue(m.runOnce())
        assertNull(s.getString("auth.token"))
    }

    @Test fun itRunsOnlyOnce() {
        val s = store("auth.token" to "legacy", "push.endpoint" to "https://x/old")
        val m = PushMigration(s, unregister = { _, _ -> true }, unregisterDefaultInstance = {})
        assertTrue(m.runOnce())
        assertFalse(m.runOnce())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PushMigrationTest*"`
Expected: FAIL — unresolved `PushMigration`.

- [ ] **Step 3: Write the implementation**

```kotlin
package tech.whitewolf.app.push

import tech.whitewolf.app.auth.SecureStore

/**
 * One-shot retirement of the pre-restructure push registration.
 *
 * Order matters and is the reason this class exists: unregistering needs the legacy
 * bearer, and the backend prunes an endpoint only on a 404/410 from the push server —
 * which ntfy does not return for an unsubscribed topic. Clearing the bearer first would
 * leave a dead row in the registry forever.
 *
 * Deletable one release after it ships.
 */
class PushMigration(
    private val store: SecureStore,
    private val unregister: (bearer: String, endpoint: String) -> Boolean,
    private val unregisterDefaultInstance: () -> Unit,
) {
    private val legacyToken = "auth.token"
    private val legacyExpires = "auth.expires"
    private val legacyEndpoint = "push.endpoint"
    private val doneKey = "migration.pushInstances"

    /** Returns true when it did work. Safe to call on every startup. */
    fun runOnce(): Boolean {
        if (store.getString(doneKey) != null) return false
        val bearer = store.getString(legacyToken)
        val endpoint = store.getString(legacyEndpoint)
        if (bearer == null && endpoint == null) {
            store.putString(doneKey, "1")
            return false
        }
        if (bearer != null && endpoint != null) {
            // Best effort: an offline first run must still clear the legacy credential.
            runCatching { unregister(bearer, endpoint) }
        }
        runCatching { unregisterDefaultInstance() }
        store.remove(legacyToken)
        store.remove(legacyExpires)
        store.remove(legacyEndpoint)
        store.putString(doneKey, "1")
        return true
    }
}
```

Call it from `AppContainer`'s init, off the main thread, before `PushManager.enable()`
first runs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PushMigrationTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushMigration.kt \
        app/src/main/java/tech/whitewolf/app/AppContainer.kt \
        app/src/test/java/tech/whitewolf/app/push/PushMigrationTest.kt
git commit -m "feat(push): retire the legacy endpoint before clearing the legacy bearer"
```

---

### Task 21: Documentation

**Files:**
- Modify: `README.md` — sign-in section, the `-P` override section, key-files table
- Modify: `docs/PUSH.md` — per-sub-app instances; the content-free payload guarantee restated
- Modify: `SECURITY.md` — the device now stores a long-lived suite-wide refresh token
- Modify: `CONTRIBUTING.md` — an OIDC provider is now part of the minimum setup
- Modify (paired repo): `email-client-maileroo/docs/AUTH.md` — its line "There is no
  refresh endpoint. When the token expires after 7 days, the client signs in again" is no
  longer true, and the Authelia client block gains `offline_access` and `refresh_token`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Update `README.md`**

Replace the two sign-in bullets with SSO-only. Update the key-files table: `subapp/SubApp.kt`
(the interface), `subapp/SubAppRegistry.kt` (instance registry), `auth/IdentityRepository.kt`
(root credential, single-flight refresh), `auth/BackendSession.kt` (per-backend mint),
`ui/RouteState.kt` (navigation), `push/DeepLink.kt`. Remove the `SubAppWebView.kt` row.

In "Pointing a build at another backend", add that an OIDC provider is now required
because password login is gone, and that the redirect URI must be registered with it.

- [ ] **Step 2: Update `docs/PUSH.md`**

Add a section stating that the app registers one UnifiedPush instance per sub-app, so a
distributor shows one registration per sub-app; and restate the guarantee precisely: the
push payload carries no content, and where a notification shows a title or thumbnail the
app fetched it over the authenticated API after the wake arrived — the push server never
sees it.

- [ ] **Step 3: Update `SECURITY.md` and `CONTRIBUTING.md`**

`SECURITY.md`: the device stores a long-lived suite-wide OIDC refresh token in
`EncryptedSharedPreferences`, alongside per-backend session bearers; sign-out revokes the
refresh token at the provider where a revocation endpoint is available.

`CONTRIBUTING.md`: the "what you can't test locally" note now includes that any build
needs an OIDC provider, not just a mail backend.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: PASS; APK builds. Re-read each changed doc top to bottom for statements the
restructure has falsified — particularly any remaining mention of password sign-in, of a
single push registration, or of `SubAppWebView`.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/PUSH.md SECURITY.md CONTRIBUTING.md
git commit -m "docs: SSO-only sign-in, per-sub-app push instances, refresh-token storage"
```

---

---

## Device testing

`scripts/browserstack-espresso.sh` runs the instrumented suite on real hardware via
BrowserStack App Automate (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
first). Espresso rather than Appium: Compose `testTag`s are not in the accessibility tree,
and Espresso runs in-process, which is the only way to seed a signed-in session once
password login is gone.

**Run it at the end of Phases 2, 3 and 4.** These behaviours cannot be covered by a JVM
test and are the reason the device run is worth its minutes:

- WebView retention: tile → back → tile performs **one** `loadUrl`, history survives, and
  no "child already has a parent". Assert with a counting `WebViewHandle` wrapper.
- Back ordering: mail's history walk wins over the shell handler; the error screen returns
  to the launcher rather than walking history.
- Deep-link routing warm and cold: launch `MainActivity` with a `wwt://subapp/mail` data
  intent — no distributor needed.

**What BrowserStack cannot cover:** anything requiring a UnifiedPush distributor — Task 14's
per-instance registration and Task 20's migration. BrowserStack devices have no ntfy app,
so they report `NoDistributor`. Those need a real device with ntfy installed.

**The plan's biggest untested risk is `AuthStateIdentity`** — the AppAuth error-code
mapping, `setNeedsTokenRefresh`, and persist-before-use. Every test above fakes `Identity`
out, so the riskiest new code has no coverage at all. Add to Task 16 an instrumented test
that points an `AuthorizationServiceConfiguration(authEndpoint, tokenEndpoint)` at a
MockWebServer running on-device (`androidTestImplementation(libs.okhttp.mockwebserver)`),
and enqueues in turn: `{"error":"invalid_grant"}` → expect `CredentialDead`; a 500 → expect
`Unreachable`; a rotated token response → expect the new refresh token persisted before the
result is returned.

## Done criteria

- `./gradlew :app:testDebugUnitTest` green, and `./gradlew :app:assembleDebug` builds.
- `grep -rn "SubAppRegistry.default\|val baseUrl" app/src/main` returns nothing.
- The launcher shows one tile (mail) and cold start opens mail directly.
- Back from mail reaches the launcher; back from the launcher exits.
- A push wake still refreshes mail silently while mail is on screen, and notifies otherwise.
- Instrumented tests pass on a real device via `scripts/browserstack-espresso.sh`. They
  cannot run in the dev sandbox, so "compiles" is not evidence — `ShellFlowTest` sat green
  in CI for weeks while failing on every real device it ever touched.
- Manual device checks that no test covers: switching launcher → mail does not reload the
  SPA; signing out and back in as a different user shows no trace of the first; a
  notification tap opens mail from cold and warm start.
