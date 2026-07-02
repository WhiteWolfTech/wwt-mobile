# Wake-to-Sync Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a push wake-up arrives, make the mail SPA refresh immediately (silently in the foreground, on return from background) instead of only notifying/opening.

**Architecture:** A process-scoped `WakeBus` signal, owned by a new `WwtApp : Application`, is bumped by `PushReceiver.onMessage` (foreground → live refresh + no notification; background → notification + pending wake). `SubAppWebView` delivers the wake to the live WebView via `evaluateJavascript("window.wwtWake && window.wwtWake()")`; the SPA exposes `window.wwtWake()` that bumps a tick fed into `Inbox`'s existing `refreshSignal`. Native → JS only.

**Tech Stack:** Kotlin, Jetpack Compose, WebView, kotlinx-coroutines `StateFlow` (native, wwt-mobile repo); React 18 + Vitest (web, email-client-maileroo repo).

## Global Constraints

- **Bridge is native → JS only.** No `@JavascriptInterface` is added in this sub-project.
- **The wake carries no data.** `evaluateJavascript` runs the fixed literal string `window.wwtWake && window.wwtWake()` — no interpolation, ever.
- **`pendingWake` is in-memory only**, never persisted across process death.
- **Notify-vs-wake is gated:** foreground push → bump the tick only (no notification, no pending); background push → post the notification and set pending only (no tick bump).
- **Permissions unchanged:** `INTERNET`, `POST_NOTIFICATIONS`. No new permissions, no foreground service.
- **Company naming:** "White Wolf Technology" / "WWT" only.
- **Two repos:** web tasks run in `/home/dev/email-client-maileroo`; native tasks run in `/home/dev/mobile-app`. Commit in the repo the task touches.
- **Native build env (export before any gradle command; run gradle with the Bash sandbox DISABLED):**
  ```
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export ANDROID_HOME=/home/dev/android-sdk
  export PATH="/home/dev/gradle-8.10/bin:$PATH"
  ```
  Use the `gradle` command. No emulator → do NOT run `androidTest`; the gate is `gradle :app:testDebugUnitTest :app:assembleDebug`.
- **Web build env:** `cd /home/dev/email-client-maileroo/web`; `npm test` runs `vitest run`. Vitest runs in a **node** environment (no `window`/jsdom) — wake code must key off `globalThis`, which `window.wwtWake` resolves to inside the WebView.

## File Structure

**Web (email-client-maileroo):**
- Create `web/src/wake.ts` — installs `globalThis.wwtWake`; returns an uninstaller. One job: the native→JS entry point.
- Create `web/src/wake.test.ts` — Vitest coverage for the hook.
- Modify `web/src/App.tsx` — `wakeTick` state; install the hook; `refreshSignal={sentTick + wakeTick}`.

**Native (wwt-mobile):**
- Create `app/src/main/java/tech/whitewolf/app/push/WakeBus.kt` — `WakeBus` (tick + pending), `WakeAction` enum, `wakeAction(foreground)` pure fn.
- Create `app/src/main/java/tech/whitewolf/app/ForegroundTracker.kt` — JVM-testable started-activity counter.
- Create `app/src/main/java/tech/whitewolf/app/WwtApp.kt` — `Application` hosting the shared `AppContainer`, `WakeBus`, and foreground state.
- Modify `app/src/main/AndroidManifest.xml` — `android:name=".WwtApp"`.
- Modify `app/src/main/java/tech/whitewolf/app/MainActivity.kt` — use the shared container.
- Modify `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt` — shared container in `onNewEndpoint`; foreground-aware `onMessage`.
- Modify `app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt` — deliver the wake (tick + resume-pending) to the live WebView.
- Modify `gradle/libs.versions.toml` + `app/build.gradle.kts` — add an explicit `kotlinx-coroutines-core` dependency.
- Tests: `app/src/test/java/tech/whitewolf/app/push/WakeBusTest.kt`, `app/src/test/java/tech/whitewolf/app/ForegroundTrackerTest.kt`.

---

### Task 1: Web — `window.wwtWake()` hook feeding the SPA refresh signal

**Repo:** `/home/dev/email-client-maileroo`

**Files:**
- Create: `web/src/wake.ts`
- Test: `web/src/wake.test.ts`
- Modify: `web/src/App.tsx`

**Interfaces:**
- Produces: `installWakeHook(bump: () => void): () => void` — sets `globalThis.wwtWake = bump`, returns an uninstaller that removes it. The WebView will call `window.wwtWake && window.wwtWake()`; in a browser `window === globalThis`, so setting `globalThis.wwtWake` makes `window.wwtWake` callable.

- [ ] **Step 1: Write the failing test**

Create `web/src/wake.test.ts`:
```ts
import { afterEach, expect, test, vi } from "vitest";
import { installWakeHook } from "./wake";

afterEach(() => { (globalThis as { wwtWake?: () => void }).wwtWake = undefined; });

test("installWakeHook wires globalThis.wwtWake to the bump callback", () => {
  const bump = vi.fn();
  installWakeHook(bump);
  expect(typeof globalThis.wwtWake).toBe("function");
  globalThis.wwtWake!();
  expect(bump).toHaveBeenCalledTimes(1);
});

test("the uninstaller removes the hook", () => {
  const bump = vi.fn();
  const uninstall = installWakeHook(bump);
  uninstall();
  expect(globalThis.wwtWake).toBeUndefined();
});

test("the uninstaller only removes its own hook", () => {
  const first = vi.fn();
  const uninstallFirst = installWakeHook(first);
  const second = vi.fn();
  installWakeHook(second); // replaces first
  uninstallFirst();        // must NOT remove second's hook
  expect(typeof globalThis.wwtWake).toBe("function");
  globalThis.wwtWake!();
  expect(second).toHaveBeenCalledTimes(1);
  expect(first).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/dev/email-client-maileroo/web && npm test`
Expected: FAIL — `Failed to resolve import "./wake"` (module does not exist yet).

- [ ] **Step 3: Write the implementation**

Create `web/src/wake.ts`:
```ts
declare global {
  // eslint-disable-next-line no-var
  var wwtWake: (() => void) | undefined;
}

/**
 * Install the native wake hook. The native shell calls `window.wwtWake()`
 * (via WebView.evaluateJavascript) to force an immediate mailbox refresh.
 * Keyed off `globalThis` so it works both in the WebView (window === globalThis)
 * and under Vitest's node environment. Returns an uninstaller.
 */
export function installWakeHook(bump: () => void): () => void {
  globalThis.wwtWake = bump;
  return () => {
    if (globalThis.wwtWake === bump) globalThis.wwtWake = undefined;
  };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/dev/email-client-maileroo/web && npm test`
Expected: PASS — all three `wake.test.ts` tests green, existing tests still green.

- [ ] **Step 5: Wire the hook into `App.tsx`**

In `web/src/App.tsx`:

Add the import near the other local imports (after `import { Settings } ...`):
```ts
import { installWakeHook } from "./wake";
```

Add the `wakeTick` state immediately after the existing `sentTick` state (`const [sentTick, setSentTick] = useState(0);`):
```ts
  // Bumped by the native shell (window.wwtWake) when a push wake-up arrives, so
  // the mailbox refreshes immediately instead of waiting for the background poll.
  const [wakeTick, setWakeTick] = useState(0);
```

Add an effect that installs the hook, next to the other top-level effects (after the `api.me()` probe effect):
```ts
  useEffect(() => installWakeHook(() => setWakeTick((n) => n + 1)), []);
```

Change the `Inbox` render to combine both signals — replace:
```tsx
        {view === "mailbox" && me && <Inbox me={me} onOpen={setOpenId} selectedId={openId} refreshSignal={sentTick} />}
```
with:
```tsx
        {view === "mailbox" && me && <Inbox me={me} onOpen={setOpenId} selectedId={openId} refreshSignal={sentTick + wakeTick} />}
```
(`sentTick` and `wakeTick` each only ever increment, so the sum is monotonic — `Inbox`'s existing `lastSignal` guard fires a refresh on any change and never on mount.)

- [ ] **Step 6: Verify the full web check passes**

Run: `cd /home/dev/email-client-maileroo/web && npm test && npx tsc -b`
Expected: Vitest PASS (incl. `wake.test.ts`); `tsc -b` completes with no type errors (the `declare global` + `globalThis.wwtWake` typecheck).

- [ ] **Step 7: Commit**

```bash
cd /home/dev/email-client-maileroo
git add web/src/wake.ts web/src/wake.test.ts web/src/App.tsx
git commit -m "$(cat <<'EOF'
feat(web): window.wwtWake() hook to refresh mailbox on native push wake

Native shell calls window.wwtWake() to force an immediate Inbox refetch via
the existing refreshSignal path (sentTick + wakeTick).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
EOF
)"
```

---

### Task 2: Native — `WakeBus` signal + `wakeAction` decision

**Repo:** `/home/dev/mobile-app`

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/java/tech/whitewolf/app/push/WakeBus.kt`
- Test: `app/src/test/java/tech/whitewolf/app/push/WakeBusTest.kt`

**Interfaces:**
- Produces:
  - `class WakeBus` with `val tick: StateFlow<Long>` (starts at `0L`), `fun signalWakeForeground()` (increments the tick), `fun signalWakeBackground()` (sets pending), `fun consumePending(): Boolean` (returns pending then clears it).
  - `enum class WakeAction { Foreground, Background }` and `fun wakeAction(foreground: Boolean): WakeAction`.

- [ ] **Step 1: Add the explicit coroutines-core dependency**

`StateFlow` is only on the classpath transitively today. Make it a direct dependency.

In `gradle/libs.versions.toml`, under `[libraries]`, add (next to `coroutines-test`):
```toml
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
```

In `app/build.gradle.kts`, in the `dependencies { }` block near the other `implementation(...)` lines, add:
```kotlin
    implementation(libs.coroutines.core)
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/tech/whitewolf/app/push/WakeBusTest.kt`:
```kotlin
package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeBusTest {
    @Test fun tickStartsAtZero() {
        assertEquals(0L, WakeBus().tick.value)
    }

    @Test fun foregroundWakeIncrementsTickAndSetsNoPending() {
        val bus = WakeBus()
        bus.signalWakeForeground()
        assertEquals(1L, bus.tick.value)
        assertFalse(bus.consumePending())
    }

    @Test fun backgroundWakeSetsPendingAndDoesNotBumpTick() {
        val bus = WakeBus()
        bus.signalWakeBackground()
        assertEquals(0L, bus.tick.value)
        assertTrue(bus.consumePending())
    }

    @Test fun consumePendingClearsAfterFirstRead() {
        val bus = WakeBus()
        bus.signalWakeBackground()
        assertTrue(bus.consumePending())
        assertFalse(bus.consumePending())
    }

    @Test fun wakeActionMapsForegroundFlag() {
        assertEquals(WakeAction.Foreground, wakeAction(true))
        assertEquals(WakeAction.Background, wakeAction(false))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "tech.whitewolf.app.push.WakeBusTest"`
Expected: FAIL — `Unresolved reference: WakeBus` (and `wakeAction`).

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/tech/whitewolf/app/push/WakeBus.kt`:
```kotlin
package tech.whitewolf.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What to do with a wake-up, decided purely from the app's foreground state. */
enum class WakeAction { Foreground, Background }

/** Foreground → refresh live (bump tick); background → notify + pending. */
fun wakeAction(foreground: Boolean): WakeAction =
    if (foreground) WakeAction.Foreground else WakeAction.Background

/**
 * Process-scoped wake signal. Level-triggered ("something changed, refetch") and
 * data-free. `tick` drives a live refresh while the app is foregrounded; `pending`
 * carries a wake that arrived while backgrounded until the next foreground resume.
 * In-memory only — never persisted (a dead process cold-starts fresh anyway).
 */
class WakeBus {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    @Volatile private var pending = false

    /** Foreground wake: refresh now. Bumps the tick; sets no pending. */
    fun signalWakeForeground() { _tick.value = _tick.value + 1 }

    /** Background wake: refresh on return. Sets pending; does not bump the tick. */
    @Synchronized fun signalWakeBackground() { pending = true }

    /** Returns whether a background wake is pending, clearing it. */
    @Synchronized fun consumePending(): Boolean {
        val p = pending
        pending = false
        return p
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests "tech.whitewolf.app.push.WakeBusTest"`
Expected: PASS — all 5 tests green.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/tech/whitewolf/app/push/WakeBus.kt \
        app/src/test/java/tech/whitewolf/app/push/WakeBusTest.kt
git commit -m "$(cat <<'EOF'
feat(bridge): WakeBus process-scoped wake signal + wakeAction decision

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
EOF
)"
```

---

### Task 3: Native — `WwtApp` Application (shared container + foreground state)

**Repo:** `/home/dev/mobile-app`

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/ForegroundTracker.kt`
- Create: `app/src/main/java/tech/whitewolf/app/WwtApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/tech/whitewolf/app/MainActivity.kt`, `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt`
- Test: `app/src/test/java/tech/whitewolf/app/ForegroundTrackerTest.kt`

**Interfaces:**
- Consumes: `WakeBus` (Task 2); `AppContainer(context)` with `.pushApiClient` / `.pushEndpointStore` (existing).
- Produces:
  - `class ForegroundTracker` with `fun onStart()`, `fun onStop()`, `val isForeground: Boolean`.
  - `class WwtApp : Application` with `val container: AppContainer`, `val wakeBus: WakeBus`, `val isForeground: Boolean`, and `companion object { fun from(context: Context): WwtApp }`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/tech/whitewolf/app/ForegroundTrackerTest.kt`:
```kotlin
package tech.whitewolf.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    @Test fun startsInBackground() {
        assertFalse(ForegroundTracker().isForeground)
    }

    @Test fun oneStartedActivityIsForeground() {
        val t = ForegroundTracker()
        t.onStart()
        assertTrue(t.isForeground)
    }

    @Test fun backgroundOnceAllActivitiesStopped() {
        val t = ForegroundTracker()
        t.onStart(); t.onStart()   // e.g. activity re-created over itself
        t.onStop()
        assertTrue(t.isForeground) // still one started
        t.onStop()
        assertFalse(t.isForeground)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "tech.whitewolf.app.ForegroundTrackerTest"`
Expected: FAIL — `Unresolved reference: ForegroundTracker`.

- [ ] **Step 3: Implement `ForegroundTracker`**

Create `app/src/main/java/tech/whitewolf/app/ForegroundTracker.kt`:
```kotlin
package tech.whitewolf.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts started activities to answer "is the app in the foreground?". Read from a
 * background thread (PushReceiver), so backed by an atomic counter.
 */
class ForegroundTracker {
    private val started = AtomicInteger(0)
    val isForeground: Boolean get() = started.get() > 0
    fun onStart() { started.incrementAndGet() }
    fun onStop() { started.decrementAndGet() }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests "tech.whitewolf.app.ForegroundTrackerTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Implement `WwtApp`**

Create `app/src/main/java/tech/whitewolf/app/WwtApp.kt`:
```kotlin
package tech.whitewolf.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import tech.whitewolf.app.push.WakeBus

/**
 * Process root: one shared AppContainer (so receivers/activities don't each build
 * their own dependency graph), the process-scoped WakeBus, and foreground state.
 */
class WwtApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    val wakeBus = WakeBus()

    private val foreground = ForegroundTracker()
    val isForeground: Boolean get() = foreground.isForeground

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foreground.onStart() }
            override fun onActivityStopped(activity: Activity) { foreground.onStop() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        fun from(context: Context): WwtApp = context.applicationContext as WwtApp
    }
}
```

- [ ] **Step 6: Register `WwtApp` in the manifest**

In `app/src/main/AndroidManifest.xml`, add the `android:name` attribute to the `<application>` tag. Change:
```xml
    <application
        android:label="WWT"
```
to:
```xml
    <application
        android:name=".WwtApp"
        android:label="WWT"
```

- [ ] **Step 7: Route `MainActivity` and `PushReceiver.onNewEndpoint` through the shared container**

In `app/src/main/java/tech/whitewolf/app/MainActivity.kt`, replace:
```kotlin
        val container = AppContainer(this)
```
with:
```kotlin
        val container = WwtApp.from(this).container
```

In `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt`, in `onNewEndpoint`'s background thread, replace:
```kotlin
                val container = AppContainer(app)
```
with:
```kotlin
                val container = tech.whitewolf.app.WwtApp.from(app).container
```
Then remove the now-unused `import tech.whitewolf.app.AppContainer` at the top of `PushReceiver.kt`.

- [ ] **Step 8: Verify the unit suite and build**

Run: `gradle :app:testDebugUnitTest :app:assembleDebug`
Expected: unit suite PASS (incl. `ForegroundTrackerTest`, `WakeBusTest`, existing tests); `assembleDebug` SUCCESSFUL (WwtApp compiles and the manifest merges with `android:name=".WwtApp"`).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ForegroundTracker.kt \
        app/src/main/java/tech/whitewolf/app/WwtApp.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/tech/whitewolf/app/MainActivity.kt \
        app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt \
        app/src/test/java/tech/whitewolf/app/ForegroundTrackerTest.kt
git commit -m "$(cat <<'EOF'
feat(bridge): WwtApp Application — shared AppContainer, WakeBus, foreground state

Also routes MainActivity and PushReceiver.onNewEndpoint through the single
app-scoped container instead of constructing one per use.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
EOF
)"
```

---

### Task 4: Native — foreground-aware `PushReceiver.onMessage`

**Repo:** `/home/dev/mobile-app`

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt`

**Interfaces:**
- Consumes: `WwtApp.from(context)` → `.isForeground`, `.wakeBus` (Task 3); `wakeAction(...)`, `WakeAction`, `WakeBus.signalWakeForeground()/signalWakeBackground()` (Task 2); `Notifications.showNewMail(context)` (existing).

- [ ] **Step 1: Rewrite `onMessage` to be foreground-aware**

In `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt`, replace the current `onMessage`:
```kotlin
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Notifications.showNewMail(context.applicationContext)
    }
```
with:
```kotlin
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val app = tech.whitewolf.app.WwtApp.from(context)
        when (wakeAction(app.isForeground)) {
            // Foreground: the user is looking at the app — refresh the mailbox
            // silently, no notification.
            WakeAction.Foreground -> app.wakeBus.signalWakeForeground()
            // Background: notify, and remember to refresh when the app returns.
            WakeAction.Background -> {
                Notifications.showNewMail(app)
                app.wakeBus.signalWakeBackground()
            }
        }
    }
```
(`wakeAction`, `WakeAction`, and the `WakeBus` methods are in the same `tech.whitewolf.app.push` package, so no new imports are needed.)

- [ ] **Step 2: Verify the unit suite and build**

Run: `gradle :app:testDebugUnitTest :app:assembleDebug`
Expected: unit suite PASS (`wakeAction` behavior is already covered by `WakeBusTest`); `assembleDebug` SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt
git commit -m "$(cat <<'EOF'
feat(bridge): foreground-aware onMessage — live refresh vs notify

Foreground push bumps the WakeBus tick (silent refresh, no notification);
background push notifies and sets a pending wake.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
EOF
)"
```

---

### Task 5: Native — deliver the wake to the live WebView in `SubAppWebView`

**Repo:** `/home/dev/mobile-app`

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt`

**Interfaces:**
- Consumes: `WwtApp.from(context).wakeBus` → `tick: StateFlow<Long>`, `consumePending(): Boolean` (Tasks 2–3).
- Produces: nothing for later tasks (leaf UI). Delivers `evaluateJavascript("window.wwtWake && window.wwtWake()")` on a foreground tick change (after page load) and once on resume when a background wake is pending.

> This task has no JVM unit test — it is Compose/WebView glue whose behavior needs an instrumented test (written, deferred: no emulator). The gate is a clean compile via `assembleDebug`, plus the manual e2e in "Final verification". Keep the diff minimal and the `evaluateJavascript` string identical to Task 4's literal.

- [ ] **Step 1: Add the wake-delivery wiring to `SubAppWebView`**

The current file builds a `WebView` inside `AndroidView(factory = { ... })` and reports load via `onPageFinished`. Make these edits:

**(a) Add imports** (with the existing `androidx.compose.*` / `androidx.webkit` imports):
```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import tech.whitewolf.app.WwtApp
```

**(b) Add a file-level constant** (top of the file, after the imports, before the `@Composable`):
```kotlin
private const val WAKE_JS = "window.wwtWake && window.wwtWake()"
```

**(c) Inside the composable, before the `AndroidView(...)` call**, add the wake state and effects:
```kotlin
    val wakeBus = remember { WwtApp.from(context).wakeBus }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    val tick by wakeBus.tick.collectAsState()

    // Foreground wake: bump arrives while the app is open → refresh once the page
    // is ready. StateFlow holds the latest tick, so a wake that lands before load
    // is applied when pageLoaded flips true (no missed wake). tick starts at 0.
    LaunchedEffect(tick, pageLoaded) {
        if (pageLoaded && tick > 0L) {
            webView?.evaluateJavascript(WAKE_JS, null)
        }
    }

    // Background wake: consumed once on the next resume, after the page is ready.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, pageLoaded) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pageLoaded && wakeBus.consumePending()) {
                webView?.evaluateJavascript(WAKE_JS, null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView = null
        }
    }
```
(`context` is already defined in the composable as `val context = LocalContext.current`.)

**(d) Capture the WebView instance** — in the `AndroidView(factory = { ctx -> ... })`, change the returned `WebView(ctx).apply { ... }` so the instance is stored. Append `.also { webView = it }` to the `WebView(ctx).apply { ... }` expression (it is the last expression in the factory lambda):
```kotlin
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            // ... all existing setup unchanged ...
        }.also { webView = it }
    })
```

**(e) Mark the page loaded** — in the `WebViewClient`'s `onPageFinished`, set `pageLoaded` in addition to the existing callback. Change:
```kotlin
                override fun onPageFinished(view: WebView, url: String) { onPageLoaded() }
```
to:
```kotlin
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = true
                    onPageLoaded()
                }
```

- [ ] **Step 2: Verify the build**

Run: `gradle :app:testDebugUnitTest :app:assembleDebug`
Expected: unit suite PASS (unchanged); `assembleDebug` SUCCESSFUL. If `androidx.compose.ui.platform.LocalLifecycleOwner` resolves as deprecated, that is expected (Compose BOM 2024.09) — a deprecation warning is acceptable; do not add a new dependency to avoid it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt
git commit -m "$(cat <<'EOF'
feat(bridge): deliver push wake to the WebView (foreground tick + resume pending)

SubAppWebView pokes window.wwtWake() on a foreground WakeBus tick (after page
load) and once on resume when a background wake is pending.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
EOF
)"
```

---

## Final verification (operator / manual e2e)

No emulator in CI, so the instrumented paths are exercised by hand once both repos are deployed (web-first, then a new APK):

1. **Foreground live refresh:** open the app on the inbox → send yourself a test email → the inbox updates within ~1s **with no notification**.
2. **Background notify + resume refresh:** background the app → send a test email → a "New mail" notification appears → tap it → the inbox is already fresh on open.
3. **Cold start:** force-stop the app → send a test email → notification → tap → app launches straight into a fresh inbox (mount fetch; no double refresh).
4. **No-distributor / plain browser regression:** the web build in a normal browser (no native shell) behaves exactly as before — `window.wwtWake` is simply never called.

## Repos & PRs

- **email-client-maileroo:** Task 1 → its own PR; deploy so the `window.wwtWake` hook exists before the native side ships.
- **wwt-mobile:** Tasks 2–5 → one PR to `master`.

---

## Self-Review

**Spec coverage (against `2026-07-02-wake-to-sync-bridge-design.md`):**
- §2/§3 foreground → silent refresh; background → notify + pending → Task 4. ✓
- §3 native→JS only, fixed literal `window.wwtWake && window.wwtWake()` → `WAKE_JS` const, Tasks 4-caller/5. ✓ · pending in-memory + gated → `WakeBus` (Task 2) + `onMessage` branch (Task 4). ✓
- §4 `WwtApp` (shared container + foreground) → Task 3; `WakeBus` → Task 2; `PushReceiver` → Task 4; `SubAppWebView` delivery → Task 5; manifest → Task 3; web `App.tsx` hook → Task 1. ✓
- §5 data flow (foreground/background/cold-start) → Tasks 4 + 5 + Final verification. ✓
- §6 error handling (not-ready → applied on load; missing `wwtWake` → `&&` no-op; coalesced; killed-before-tap; ref cleared on dispose) → Task 5 effects + `onDispose { webView = null }`. ✓
- §7 security (no `@JavascriptInterface`; literal string) → no JS-interface task exists; `WAKE_JS` constant. ✓
- §8 permissions unchanged → no manifest permission change (only `android:name`). ✓
- §9 testing (web Vitest; JVM `WakeBus`/foreground; instrumented deferred) → Tasks 1, 2, 3; Task 5 notes deferral. ✓
- §10 follow-ups → logged in the spec + memory, no task (correct). ✓
- §11/§12 sequencing web-first → Task 1 first, "Repos & PRs". ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code; commands have expected output.

**Type/identifier consistency:** `WakeBus` API (`tick`/`signalWakeForeground`/`signalWakeBackground`/`consumePending`) identical across Tasks 2, 4, 5. `WakeAction`/`wakeAction` identical across Tasks 2, 4. `WwtApp.from(context)` + `.container`/`.wakeBus`/`.isForeground` identical across Tasks 3, 4, 5. `ForegroundTracker` (`onStart`/`onStop`/`isForeground`) consistent (Task 3). `installWakeHook` + `globalThis.wwtWake` + `refreshSignal={sentTick + wakeTick}` consistent (Task 1). `WAKE_JS` literal matches the string used in Task 4's comment and Task 5. ✓
