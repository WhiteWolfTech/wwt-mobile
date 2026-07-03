# Push-Distributor Status UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the WWT app's push-distributor UX honest and live — detect a mis-pointed ntfy distributor, show a proper banner above the WebView, and re-check on resume and on a 30s foreground poll.

**Architecture:** A pure `pushStatusForEndpoint()` classifies the distributor's endpoint host against a pinned `BuildConfig.NTFY_HOST` into a three-state `PushStatus`. A process-scoped `PushStatusBus` (`StateFlow<PushStatus>`) on `WwtApp` carries the current state; `PushReceiver.onNewEndpoint` publishes it. `ShellScreen` collects it, renders a Material banner (replacing the transparent overlay), and re-drives status on first entry, on `ON_RESUME`, and from a 30s foreground poll that runs only while a problem banner is showing.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), UnifiedPush connector, coroutines StateFlow, Android lifecycle. JVM unit tests (JUnit4). No emulator.

## Global Constraints

- **Single PR, wwt-mobile only.** No backend and no web changes.
- **New BuildConfig field:** `NTFY_HOST = "ntfy.whitewolf.tech"` in **both** `debug` and `release` build types.
- **Three states only:** `PushStatus.Ok` (no banner), `PushStatus.NoDistributor`, `PushStatus.WrongServer(endpointHost)`.
- **Host compare is case-insensitive and ignores port** (compare host only).
- **Unparseable endpoint → `Ok`** (never a false alarm). `NoDistributor` is set only by `ShellScreen`, never by `pushStatusForEndpoint`.
- **Banner = guidance text + Dismiss only.** No deep-links, no test action, no intent handling.
- **Periodic poll:** 30s cadence, foreground only, problem states only; stops on `Ok`.
- **Dismissal is per-session** (in-memory, not persisted across launches).
- **No new permissions, no new network calls, no `@JavascriptInterface`.**
- **Packages:** status logic in `tech.whitewolf.app.push`; banner composable in `tech.whitewolf.app.ui`.
- **Toolchain:** run gradle via `./gradlew` with the Bash sandbox disabled (it needs network/filesystem). No emulator → JVM unit tests (`:app:testDebugUnitTest`) + `:app:assembleDebug` are the gate. compileSdk 35, Kotlin/JVM target 17.

---

### Task 1: `PushStatus` type + `pushStatusForEndpoint`

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/PushStatus.kt`
- Test: `app/src/test/java/tech/whitewolf/app/push/PushStatusTest.kt`

**Interfaces:**
- Consumes: nothing (leaf).
- Produces:
  - `sealed interface PushStatus { object Ok; object NoDistributor; data class WrongServer(val endpointHost: String) }`
  - `fun pushStatusForEndpoint(endpoint: String, expectedHost: String): PushStatus`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/tech/whitewolf/app/push/PushStatusTest.kt`:

```kotlin
package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushStatusTest {
    @Test fun endpointOnExpectedHostIsOk() {
        assertEquals(
            PushStatus.Ok,
            pushStatusForEndpoint("https://ntfy.whitewolf.tech/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun endpointOnDifferentHostIsWrongServer() {
        assertEquals(
            PushStatus.WrongServer("ntfy.sh"),
            pushStatusForEndpoint("https://ntfy.sh/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun hostCompareIsCaseInsensitive() {
        assertEquals(
            PushStatus.Ok,
            pushStatusForEndpoint("https://NTFY.WhiteWolf.Tech/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun portIsIgnored() {
        assertEquals(
            PushStatus.Ok,
            pushStatusForEndpoint("https://ntfy.whitewolf.tech:8443/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun unparseableEndpointIsOk() {
        assertEquals(PushStatus.Ok, pushStatusForEndpoint("not a url", "ntfy.whitewolf.tech"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.push.PushStatusTest'`
Expected: FAIL — compilation error, `PushStatus` / `pushStatusForEndpoint` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/tech/whitewolf/app/push/PushStatus.kt`:

```kotlin
package tech.whitewolf.app.push

import java.net.URI

/**
 * Health of push delivery, derived from the distributor and its endpoint.
 * Data-light and free of Android types → JVM-testable.
 */
sealed interface PushStatus {
    /** A distributor is registered and pointed at the expected server. */
    object Ok : PushStatus
    /** No UnifiedPush distributor is installed. */
    object NoDistributor : PushStatus
    /** A distributor is installed but pointed at [endpointHost], not the expected host. */
    data class WrongServer(val endpointHost: String) : PushStatus
}

/**
 * Classify a distributor endpoint by its host. The endpoint URL the distributor issues
 * *is* its configured server, so comparing its host to [expectedHost] (case-insensitive,
 * port ignored) is a precise, network-free check. An unparseable endpoint → [PushStatus.Ok]
 * (never a false alarm). Never returns [PushStatus.NoDistributor] — that is decided upstream
 * from distributor presence, not from an endpoint.
 */
fun pushStatusForEndpoint(endpoint: String, expectedHost: String): PushStatus {
    val host = try {
        URI(endpoint).host
    } catch (e: Exception) {
        null
    } ?: return PushStatus.Ok
    return if (host.equals(expectedHost, ignoreCase = true)) {
        PushStatus.Ok
    } else {
        PushStatus.WrongServer(host)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.push.PushStatusTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushStatus.kt app/src/test/java/tech/whitewolf/app/push/PushStatusTest.kt
git commit -m "feat(push): PushStatus type + endpoint-host classifier"
```

---

### Task 2: `PushStatusBus` + `BuildConfig.NTFY_HOST` + wire into `WwtApp`

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/PushStatusBus.kt`
- Test: `app/src/test/java/tech/whitewolf/app/push/PushStatusBusTest.kt`
- Modify: `app/build.gradle.kts:42-52` (both build types)
- Modify: `app/src/main/java/tech/whitewolf/app/WwtApp.kt:15` (add the bus field)

**Interfaces:**
- Consumes: `PushStatus` (Task 1).
- Produces:
  - `class PushStatusBus { val status: StateFlow<PushStatus>; fun set(s: PushStatus) }` (initial value `PushStatus.Ok`)
  - `WwtApp.pushStatusBus: PushStatusBus`
  - `BuildConfig.NTFY_HOST: String` = `"ntfy.whitewolf.tech"`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/tech/whitewolf/app/push/PushStatusBusTest.kt`:

```kotlin
package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushStatusBusTest {
    @Test fun initialStatusIsOk() {
        assertEquals(PushStatus.Ok, PushStatusBus().status.value)
    }

    @Test fun setNoDistributorUpdatesStatus() {
        val bus = PushStatusBus()
        bus.set(PushStatus.NoDistributor)
        assertEquals(PushStatus.NoDistributor, bus.status.value)
    }

    @Test fun setWrongServerRoundTrips() {
        val bus = PushStatusBus()
        bus.set(PushStatus.WrongServer("ntfy.sh"))
        assertEquals(PushStatus.WrongServer("ntfy.sh"), bus.status.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.push.PushStatusBusTest'`
Expected: FAIL — `PushStatusBus` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/tech/whitewolf/app/push/PushStatusBus.kt`:

```kotlin
package tech.whitewolf.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped holder for the current [PushStatus]. Starts at [PushStatus.Ok] so no
 * banner shows until a problem is actually known. Published from PushReceiver (background
 * thread) and read by the shell UI — StateFlow makes the cross-thread hand-off safe.
 */
class PushStatusBus {
    private val _status = MutableStateFlow<PushStatus>(PushStatus.Ok)
    val status: StateFlow<PushStatus> = _status

    fun set(s: PushStatus) { _status.value = s }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.push.PushStatusBusTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Add `BuildConfig.NTFY_HOST` to both build types**

In `app/build.gradle.kts`, add the field alongside the existing `MAIL_BASE_URL` line in **both** blocks.

`debug` block (currently lines 42-44):

```kotlin
        debug {
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
            buildConfigField("String", "NTFY_HOST", "\"ntfy.whitewolf.tech\"")
        }
```

`release` block (currently lines 45-51), add the same line after the `MAIL_BASE_URL` line:

```kotlin
        release {
            isMinifyEnabled = false
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
            buildConfigField("String", "NTFY_HOST", "\"ntfy.whitewolf.tech\"")
            if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
```

- [ ] **Step 6: Wire the bus into `WwtApp`**

In `app/src/main/java/tech/whitewolf/app/WwtApp.kt`, add the import and the field next to `wakeBus`.

Change the import line:

```kotlin
import tech.whitewolf.app.push.PushStatusBus
import tech.whitewolf.app.push.WakeBus
```

Add the field after `val wakeBus = WakeBus()` (line 15):

```kotlin
    val wakeBus = WakeBus()
    val pushStatusBus = PushStatusBus()
```

- [ ] **Step 7: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Confirms `NTFY_HOST` generates and `WwtApp` compiles with the new field.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushStatusBus.kt app/src/test/java/tech/whitewolf/app/push/PushStatusBusTest.kt app/build.gradle.kts app/src/main/java/tech/whitewolf/app/WwtApp.kt
git commit -m "feat(push): PushStatusBus + BuildConfig.NTFY_HOST wired into WwtApp"
```

---

### Task 3: Publish status from `PushReceiver.onNewEndpoint`

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt:15-30`

**Interfaces:**
- Consumes: `pushStatusForEndpoint` (Task 1), `WwtApp.pushStatusBus` (Task 2), `BuildConfig.NTFY_HOST` (Task 2).
- Produces: no new symbols — a side effect (`pushStatusBus.set(...)`) on every new endpoint.

**Note:** no JVM test — `PushReceiver` is an Android `MessagingReceiver` with no emulator here. Its only new logic (`pushStatusForEndpoint`) is already unit-tested in Task 1; the gate is `assembleDebug` plus the manual e2e in Task 6. The `set(...)` is placed **immediately after `save(endpoint)`**, before the backend `register()` call, so a host mismatch is surfaced even when the backend register fails (a correct-host transient register failure is deliberately not surfaced — see spec §6).

- [ ] **Step 1: Add the publish call**

In `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt`, update `onNewEndpoint` (lines 15-30) so the body of the `Thread` reads:

```kotlin
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val wwtApp = tech.whitewolf.app.WwtApp.from(app)
                val container = wwtApp.container
                container.pushEndpointStore.save(endpoint)
                // Surface push health from the endpoint host now — independent of whether
                // the backend register below succeeds.
                wwtApp.pushStatusBus.set(
                    pushStatusForEndpoint(endpoint, tech.whitewolf.app.BuildConfig.NTFY_HOST)
                )
                val ok = container.pushApiClient.register(endpoint)
                if (!ok) android.util.Log.w("PushReceiver", "push endpoint registration failed")
            } catch (e: Throwable) {
                android.util.Log.w("PushReceiver", "push endpoint registration error", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing tests + Tasks 1-2).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt
git commit -m "feat(push): publish PushStatus from onNewEndpoint"
```

---

### Task 4: `PushStatusBanner` composable + `pushBannerText`

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt`
- Test: `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt`

**Interfaces:**
- Consumes: `PushStatus` (Task 1).
- Produces:
  - `fun pushBannerText(status: PushStatus): String?` — the per-state copy; `null` for `Ok`.
  - `@Composable fun PushStatusBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier)`

**Note:** `pushBannerText` is a plain (non-`@Composable`) top-level function, so it is JVM-unit-testable even though the file also declares a composable — the composable is never invoked by the test. The banner's visibility and dismissal logic live in `ShellScreen` (Task 5); this composable is a dumb renderer.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt`:

```kotlin
package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okHasNoBannerText() {
        assertNull(pushBannerText(PushStatus.Ok))
    }

    @Test fun noDistributorTextMentionsObtainiumAndHost() {
        val text = pushBannerText(PushStatus.NoDistributor)
        assertEquals(
            "Notifications are off. Install the ntfy app via Obtainium and set its " +
                "server to ntfy.whitewolf.tech.",
            text,
        )
    }

    @Test fun wrongServerTextNamesTheWrongHost() {
        val text = pushBannerText(PushStatus.WrongServer("ntfy.sh"))
        assertTrue(text!!.contains("ntfy.sh"))
        assertTrue(text.contains("ntfy.whitewolf.tech"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: FAIL — `pushBannerText` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt`:

```kotlin
package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.push.PushStatus

private const val EXPECTED_HOST = "ntfy.whitewolf.tech"

/** Guidance copy for a push-status banner, or null when there is nothing to show ([PushStatus.Ok]). */
fun pushBannerText(status: PushStatus): String? = when (status) {
    is PushStatus.Ok -> null
    is PushStatus.NoDistributor ->
        "Notifications are off. Install the ntfy app via Obtainium and set its " +
            "server to $EXPECTED_HOST."
    is PushStatus.WrongServer ->
        "ntfy is installed but pointed at ${status.endpointHost}. Open ntfy and set its " +
            "server to $EXPECTED_HOST for notifications."
}

/**
 * A dismissible status banner shown above the WebView. Guidance text + Dismiss only —
 * no actions or deep-links. Visibility and dismissal are decided by the caller.
 */
@Composable
fun PushStatusBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("pushBanner"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        ) {
            Text(text = text, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(start = 8.dp).testTag("pushBannerDismiss"),
            ) { Text("Dismiss") }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt
git commit -m "feat(push): PushStatusBanner composable + per-state copy"
```

---

### Task 5: `ShellScreen` integration — banner, recheck, resume, periodic poll

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` (whole-file rewrite of the logged-in body)

**Interfaces:**
- Consumes: `PushStatus` (Task 1), `WwtApp.pushStatusBus` (Task 2), `PushStatusBanner` + `pushBannerText` (Task 4), existing `PushManager.hasDistributor()`/`enable()`/`disable()`, `WwtApp.from(context)`.
- Produces: no new symbols. Removes the old `showPushHint` state and the transparent `Text` overlay.

**Behavior:**
- A single `recheck()` closure drives status: no distributor → `set(NoDistributor)`; else `enable()` (→ `onNewEndpoint` publishes `Ok`/`WrongServer`).
- `recheck()` runs on first entry, on `ON_RESUME`, and from a 30s poll.
- The poll `LaunchedEffect` is keyed on `isProblem` (`pushStatus !is Ok`): it exists only in a problem state, each tick gated on the lifecycle being `RESUMED` so nothing runs backgrounded, and it self-cancels when status reaches `Ok`. (This is the dependency-free equivalent of `repeatOnLifecycle(RESUMED)` — it uses `Lifecycle.currentState`, already on the classpath, instead of adding `lifecycle-runtime-compose`.)
- The banner sits at the top of a `Column`; the WebView `Box` takes the remaining space (`weight(1f)`), so the banner no longer overlays content.
- Dismissal records the current status in `dismissedStatus`; the banner shows only when `pushStatus != dismissedStatus` and `pushBannerText(pushStatus) != null`. A change to a different problem re-shows; `Ok` hides it.

- [ ] **Step 1: Rewrite `ShellScreen.kt`**

Replace the entire contents of `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` with:

```kotlin
package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.auth.LoginViewModel
import tech.whitewolf.app.push.PushManager
import tech.whitewolf.app.push.PushStatus
import tech.whitewolf.app.subapp.SubAppRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(container: AppContainer) {
    var loggedIn by remember { mutableStateOf(container.auth.isLoggedIn()) }

    if (!loggedIn) {
        val vm = remember { LoginViewModel(container.auth) }
        val state by vm.state.collectAsState()
        LaunchedEffect(state.loggedIn) { if (state.loggedIn) loggedIn = true }
        LoginScreen(state, vm::onEmail, vm::onPassword, vm::submit)
        return
    }

    val subApp = remember { SubAppRegistry.default() }
    var loading by remember { mutableStateOf(true) }
    var errored by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val pushManager = remember { PushManager(context.applicationContext) }
    val pushStatusBus = remember { WwtApp.from(context).pushStatusBus }
    val pushStatus by pushStatusBus.status.collectAsState()
    var dismissedStatus by remember { mutableStateOf<PushStatus?>(null) }

    // Re-drive push status from the current distributor state. Used on first entry, on
    // resume, and from the periodic poll. hasDistributor()/enable() are quick local
    // PackageManager/connector calls (no network); enable() re-registers, and
    // onNewEndpoint then publishes Ok/WrongServer.
    val recheck = {
        if (!pushManager.hasDistributor()) {
            pushStatusBus.set(PushStatus.NoDistributor)
        } else {
            pushManager.enable()
        }
    }

    LaunchedEffect(Unit) { recheck() }

    // Liveness on resume: catches a distributor installed/removed/reconfigured while the
    // app was backgrounded (the common "went to fix it, came back" path).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Periodic liveness while a problem banner is up and the app is foreground: catches a
    // distributor installed/reconfigured without the app ever backgrounding (e.g.
    // split-screen install). Keyed on isProblem so the loop exists only in a problem
    // state and cancels the moment status reaches Ok; each tick is gated on RESUMED so
    // nothing runs in the background.
    val isProblem = pushStatus !is PushStatus.Ok
    LaunchedEffect(isProblem) {
        if (!isProblem) return@LaunchedEffect
        while (true) {
            delay(30_000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                recheck()
            }
        }
    }

    val signOut = {
        val endpoint = container.pushEndpointStore.get()
        pushManager.disable()
        Thread {
            // Order matters: unregister uses the live bearer token, so it must run before
            // logout() clears the token. Not tied to composition, so it survives the
            // screen leaving composition when loggedIn flips.
            if (endpoint != null) container.pushApiClient.unregister(endpoint)
            container.pushEndpointStore.clear()
            container.auth.logout()
        }.start()
        loggedIn = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(subApp.title) },
                actions = { TextButton(onClick = signOut) { Text("Sign out") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val bannerText = pushStatus.takeIf { it != dismissedStatus }?.let { pushBannerText(it) }
            if (bannerText != null) {
                PushStatusBanner(
                    text = bannerText,
                    onDismiss = { dismissedStatus = pushStatus },
                )
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    errored -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Couldn't reach ${subApp.title}.")
                        Button(
                            onClick = { errored = false; loading = true; reloadKey++ },
                            modifier = Modifier.padding(top = 12.dp).testTag("retry"),
                        ) { Text("Retry") }
                        Button(
                            onClick = signOut,
                            modifier = Modifier.padding(top = 8.dp).testTag("signout"),
                        ) { Text("Sign out") }
                    }
                    else -> {
                        key(reloadKey) {
                            SubAppWebView(
                                subApp = subApp,
                                sessionToken = container.auth.currentToken(),
                                onPageError = { errored = true },
                                onPageLoaded = { loading = false },
                            )
                        }
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).testTag("progress"),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests, including any that reference `ShellScreen`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt
git commit -m "feat(push): banner + live re-check (resume + 30s foreground poll) in ShellScreen"
```

---

### Task 6: Manual end-to-end verification (operator, on device)

**Files:** none (verification only).

No emulator is available in this environment, so these are performed by the operator on a physical GrapheneOS device after installing the debug build. Record the outcome in the PR description.

- [ ] **Step 1: Build the installable debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Verify each scenario on device**

- [ ] (a) **No distributor** — with ntfy uninstalled, log in → `NoDistributor` banner: "Notifications are off. Install the ntfy app via Obtainium…".
- [ ] (b) **Wrong server** — install ntfy on its default `ntfy.sh`, return to the app → `WrongServer` banner naming `ntfy.sh`.
- [ ] (c) **Resume clears** — in ntfy set the server to `ntfy.whitewolf.tech`, switch back to the app → banner clears on resume (no relaunch).
- [ ] (d) **All correct** — with ntfy on `ntfy.whitewolf.tech`, log in → no banner.
- [ ] (e) **Push still delivers** — trigger a `new_mail` push → mailbox refreshes / notification as before (no wake-to-sync regression).
- [ ] (f) **Periodic poll** — with the `NoDistributor` banner showing, install ntfy while keeping the WWT app foreground (split-screen) → banner clears within ~30s with no app switch.
- [ ] (g) **Dismiss** — tap **Dismiss** on a banner → it hides for the session; a different problem state re-shows it.

- [ ] **Step 3: Whole-branch review + PR**

Use `superpowers:requesting-code-review` for a whole-branch review, address blocking findings, then open one PR on wwt-mobile.
