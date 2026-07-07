# Offline-Aware Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The shell's load-error screen distinguishes offline from server-unreachable and retries automatically (on reconnect, on resume, and every 30 s while online) instead of latching until a manual Retry.

**Architecture:** A small `ConnectivityMonitor` (StateFlow of "validated internet available") feeds `ShellScreen`, whose error branch picks its copy from a pure `errorMessageFor` function and gains auto-retry effects. No changes to `SubAppWebView` or the load-success path.

**Tech Stack:** Kotlin + Jetpack Compose, `ConnectivityManager` default-network callback, JUnit.

**Spec:** `docs/superpowers/specs/2026-07-07-offline-error-handling-design.md`

## Global Constraints

- No new Gradle dependencies. One new (normal, promptless) manifest permission: `android.permission.ACCESS_NETWORK_STATE`.
- "Online" means `NET_CAPABILITY_INTERNET` AND `NET_CAPABILITY_VALIDATED` — captive portals count as offline.
- Never auto-retry while offline; while online+errored the cadence is exactly: immediate on the offline→online transition, on ON_RESUME, otherwise every 30 000 ms; each retry gated on lifecycle RESUMED.
- If the network-callback registration throws, degrade to `online = true` forever (today's behavior + 30 s retry), never worse.
- The Retry/Sign out buttons and their `testTag`s (`retry`, `signout`) are unchanged in both error states.
- Verification gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug` green. Do NOT add instrumented tests; DO check `app/src/androidTest` for assertions on the old error copy ("Couldn't reach") and update any that reference it so the (locally unrunnable) suite isn't silently broken.
- Work in `/home/dev/mobile-app-worktrees/offline-handling` on branch `feat/offline-error-handling` (worktree of `/home/dev/mobile-app`, based on origin/master `76fa5a5`).

---

### Task 1: ConnectivityMonitor + offline-aware error screen + auto-retry

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/net/ConnectivityMonitor.kt`
- Test: `app/src/test/java/tech/whitewolf/app/net/ConnectivityMonitorTest.kt`
- Test: `app/src/test/java/tech/whitewolf/app/ui/ErrorMessageTest.kt`
- Modify: `app/src/main/AndroidManifest.xml` (one permission line)
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` (surgical edits below)

**Interfaces:**
- Produces: `class ConnectivityMonitor(context: Context)` with `val online: StateFlow<Boolean>`, `fun start()`, `fun stop()`, and `companion object { fun isOnline(hasInternet: Boolean, hasValidated: Boolean): Boolean }`; top-level `internal fun errorMessageFor(online: Boolean, title: String): String` in package `tech.whitewolf.app.ui`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/tech/whitewolf/app/net/ConnectivityMonitorTest.kt`:

```kotlin
package tech.whitewolf.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityMonitorTest {
    @Test
    fun `online requires both internet and validated capabilities`() {
        assertTrue(ConnectivityMonitor.isOnline(hasInternet = true, hasValidated = true))
        assertFalse(ConnectivityMonitor.isOnline(hasInternet = true, hasValidated = false))
        assertFalse(ConnectivityMonitor.isOnline(hasInternet = false, hasValidated = true))
        assertFalse(ConnectivityMonitor.isOnline(hasInternet = false, hasValidated = false))
    }
}
```

Create `app/src/test/java/tech/whitewolf/app/ui/ErrorMessageTest.kt`:

```kotlin
package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessageTest {
    @Test
    fun `online shows the server-unreachable copy`() {
        assertEquals("Couldn't reach Mail.", errorMessageFor(online = true, title = "Mail"))
    }

    @Test
    fun `offline shows the waiting-for-connection copy`() {
        assertEquals("You're offline. Waiting for a connection…", errorMessageFor(online = false, title = "Mail"))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.net.ConnectivityMonitorTest' --tests 'tech.whitewolf.app.ui.ErrorMessageTest'`
Expected: compilation FAILURE (`ConnectivityMonitor`, `errorMessageFor` do not exist).

- [ ] **Step 3: Implement `ConnectivityMonitor.kt`**

```kotlin
package tech.whitewolf.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether the device has a usable internet connection. "Usable" means
 * NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED, so captive portals and
 * dead Wi-Fi count as offline — a page load would fail on them anyway.
 *
 * start()/stop() bracket a default-network callback registration; the flow is
 * also primed synchronously so the first frame already has a real value.
 */
class ConnectivityMonitor(context: Context) {
    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = isOnline(
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

        override fun onLost(network: Network) {
            // The default network went away; re-check rather than assume offline
            // (another network may already have taken over).
            _online.value = currentlyOnline()
        }
    }

    fun start() {
        _online.value = currentlyOnline()
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // Restricted environments can refuse the registration. Degrade to
            // "always online": the UI keeps the generic message + timed retry,
            // which is exactly the pre-feature behavior.
            _online.value = true
        }
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Not registered (start() failed) — nothing to undo.
        }
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return isOnline(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    companion object {
        /** Pure decision used by the callback paths; unit-tested. */
        fun isOnline(hasInternet: Boolean, hasValidated: Boolean): Boolean =
            hasInternet && hasValidated
    }
}
```

- [ ] **Step 4: Add the manifest permission**

In `app/src/main/AndroidManifest.xml`, after the INTERNET permission line:

```xml
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 5: Edit `ShellScreen.kt`**

5a. Add the import (alphabetical among the `tech.whitewolf.app` imports):

```kotlin
import tech.whitewolf.app.net.ConnectivityMonitor
```

5b. After the `var reloadKey by remember { mutableStateOf(0) }` line, add:

```kotlin
    val retry: () -> Unit = { errored = false; loading = true; reloadKey++ }
```

…and change the existing error-branch Retry button's `onClick = { errored = false; loading = true; reloadKey++ }` to `onClick = retry`.

5c. After the `var notificationsEnabled …` line (so it precedes the lifecycle observer), add:

```kotlin
    val connectivity = remember { ConnectivityMonitor(context.applicationContext) }
    val online by connectivity.online.collectAsState()
    DisposableEffect(Unit) {
        connectivity.start()
        onDispose { connectivity.stop() }
    }
```

5d. In the existing ON_RESUME observer, extend the body:

```kotlin
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recheck(true)
                // Reopening the app is the natural "try again" moment: if the
                // error screen is up and we're online, retry without waiting
                // for the 30s tick.
                if (errored && online) retry()
            }
        }
```

5e. After the `LaunchedEffect(isProblem)` block, add the auto-retry effect and, at file top level (next to the other file-private declarations at the bottom), the constant and copy function:

```kotlin
    // Offline-aware auto-retry: a load failure no longer latches forever.
    // Immediate retry when a usable connection (re)appears; every 30s while
    // online (short server blips); never while offline. Each retry waits for
    // RESUMED so nothing reloads from the background. retry() flips `errored`,
    // which restarts this effect — a recurring failure lands back here and
    // waits the full interval, so there is no tight loop.
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(errored, online) {
        val cameOnline = online && !wasOnline
        wasOnline = online
        if (!errored || !online) return@LaunchedEffect
        if (!cameOnline) delay(ERROR_RETRY_MS)
        while (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            delay(ERROR_RETRY_MS)
        }
        retry()
    }
```

At the bottom of the file (top level):

```kotlin
private const val ERROR_RETRY_MS = 30_000L

/** Copy for the main-frame load-error screen: offline vs server-unreachable. */
internal fun errorMessageFor(online: Boolean, title: String): String =
    if (online) "Couldn't reach $title."
    else "You're offline. Waiting for a connection…"
```

5f. In the error branch, change `Text("Couldn't reach ${subApp.title}.")` to:

```kotlin
                        Text(errorMessageFor(online, subApp.title))
```

(The Retry/Sign out buttons and their `testTag`s stay exactly as they are.)

- [ ] **Step 6: Check instrumented tests for the old copy**

Run: `grep -rn "Couldn't reach" app/src/androidTest/`
If any assertion references the old copy, update it to match `errorMessageFor(online = true, …)` output (which is unchanged: "Couldn't reach Mail.") — expected: no change needed, but verify.

- [ ] **Step 7: Run tests to verify they pass, then the full gate**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.net.ConnectivityMonitorTest' --tests 'tech.whitewolf.app.ui.ErrorMessageTest'` — PASS.
Then: `./gradlew :app:testDebugUnitTest :app:assembleDebug` — BUILD SUCCESSFUL, all unit tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
  app/src/main/java/tech/whitewolf/app/net/ConnectivityMonitor.kt \
  app/src/test/java/tech/whitewolf/app/net/ConnectivityMonitorTest.kt \
  app/src/test/java/tech/whitewolf/app/ui/ErrorMessageTest.kt \
  app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt
git commit -m "feat(shell): offline-aware load-error screen with auto-retry

A transient main-frame error (network handoff, DNS blip, cold start
in a dead spot) latched the generic error screen until a manual
Retry — while the server was healthy. The error screen now says
you're offline when you are, retries immediately when a validated
connection (re)appears or the app resumes, and every 30s while
online. Never retries while offline."
```

---

## Manual verification (operator, on-device)

1. Airplane mode ON → open the app → "You're offline. Waiting for a connection…" (not "Couldn't reach").
2. Airplane mode OFF with the screen showing → mail loads within a second or two, no tap needed.
3. Normal launch → mail loads as usual (no behavior change on the success path).

## Final verification

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` green; only the five listed files changed.
