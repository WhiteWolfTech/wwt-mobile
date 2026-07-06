# Back Button + Pull-to-Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** System back navigates the WebView's history instead of exiting; a native pull-to-refresh gesture at the top of the mail list fires `window.wwtWake()`.

**Architecture:** All changes live in the WebView host layer: `SubAppWebView.kt` gains a `BackHandler` driven by `doUpdateVisitedHistory`, and its WebView gets wrapped in a `SwipeRefreshLayout` gated by a new `ShellBridge` JavaScript interface (`window.WwtShell.setAtTop` — the SPA side is already deployed).

**Tech Stack:** Kotlin + Jetpack Compose shell, classic `androidx.swiperefreshlayout` view (Compose pull-refresh cannot see WebView inner scroll), JUnit unit tests.

**Spec:** `docs/superpowers/specs/2026-07-06-back-button-pull-refresh-design.md`

## Global Constraints

- Kotlin/Compose conventions of this repo; keep UI wiring thin, logic in plain testable classes.
- One new dependency ONLY: `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` via the version catalog.
- `ShellBridge.atTop` defaults `false` — against an SPA that never calls the bridge, the gesture must never arm.
- The refresh spinner stops after 800 ms for the wake path (no completion signal); the reload path stops it on `onPageFinished`.
- Verification gate: `./gradlew :app:testDebugUnitTest` green (the repo's CI release gate) and `./gradlew :app:assembleDebug` compiles. Instrumented tests exist in the repo but cannot run here (no emulator) — do not add instrumented tests.
- Work in `/home/dev/mobile-app-worktrees/shell-back-ptr` on branch `feat/back-button-pull-refresh` (worktree of `/home/dev/mobile-app`, based on origin/master `9127427`).

---

### Task 1: ShellBridge + pull-to-refresh + back button

One task: the pieces share `SubAppWebView.kt` and reviewing them together is cheaper than splitting.

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/web/ShellBridge.kt`
- Test: `app/src/test/java/tech/whitewolf/app/web/ShellBridgeTest.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (the one new dependency)
- Modify: `app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt`

**Interfaces:**
- Produces: `class ShellBridge { @Volatile var atTop: Boolean; @JavascriptInterface fun setAtTop(v: Boolean) }`; `SubAppWebView` keeps its existing signature (`subApp`, `sessionToken`, `onPageError`, `onPageLoaded`) — callers (ShellScreen) need no changes.

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/tech/whitewolf/app/web/ShellBridgeTest.kt`:

```kotlin
package tech.whitewolf.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellBridgeTest {
    @Test
    fun `atTop defaults to false so the gesture never arms without SPA reports`() {
        assertFalse(ShellBridge().atTop)
    }

    @Test
    fun `setAtTop updates the flag both ways`() {
        val bridge = ShellBridge()
        bridge.setAtTop(true)
        assertTrue(bridge.atTop)
        bridge.setAtTop(false)
        assertFalse(bridge.atTop)
    }

    @Test
    fun `value written on another thread is visible to the reader`() {
        val bridge = ShellBridge()
        val t = Thread { bridge.setAtTop(true) }
        t.start()
        t.join()
        assertTrue(bridge.atTop)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.web.ShellBridgeTest'`
Expected: compilation FAILURE — `ShellBridge` does not exist.

- [ ] **Step 3: Implement `ShellBridge.kt`**

```kotlin
package tech.whitewolf.app.web

import android.webkit.JavascriptInterface

/**
 * SPA → shell signal injected into the WebView as `window.WwtShell`. The web
 * app reports whether its mail list is visible and scrolled to the top — the
 * only state where a downward drag should arm pull-to-refresh instead of
 * scrolling content. Defaults to false so an SPA that never reports (older
 * deploy, different page) leaves the gesture inert.
 *
 * setAtTop is invoked on the WebView's JS bridge thread while the UI thread
 * reads atTop from SwipeRefreshLayout's child-scroll callback — hence @Volatile.
 */
class ShellBridge {
    @Volatile
    var atTop: Boolean = false
        private set

    @JavascriptInterface
    fun setAtTop(v: Boolean) {
        atTop = v
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.web.ShellBridgeTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the SwipeRefreshLayout dependency**

`gradle/libs.versions.toml` — under `[versions]` add:

```toml
swiperefreshlayout = "1.1.0"
```

Under `[libraries]` add:

```toml
swiperefreshlayout = { group = "androidx.swiperefreshlayout", name = "swiperefreshlayout", version.ref = "swiperefreshlayout" }
```

`app/build.gradle.kts` — in `dependencies`, after `implementation(libs.activity.compose)`:

```kotlin
    implementation(libs.swiperefreshlayout)
```

- [ ] **Step 6: Rework `SubAppWebView.kt`**

Apply these changes (full resulting file below — replace the file with this content):

```kotlin
package tech.whitewolf.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.auth.sessionCookieLine
import tech.whitewolf.app.subapp.SubApp
import tech.whitewolf.app.web.NavPolicy
import tech.whitewolf.app.web.ShellBridge

private const val WAKE_JS = "window.wwtWake && window.wwtWake()"

// Spinner runtime for the wake-refresh path: the SPA gives no completion
// signal, and its refresh fetch is fast — a fixed short spin reads as "done".
private const val REFRESH_SPINNER_MS = 800L

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SubAppWebView(
    subApp: SubApp,
    sessionToken: String?,
    onPageError: () -> Unit,
    onPageLoaded: () -> Unit,
) {
    val context = LocalContext.current
    val wakeBus = remember { WwtApp.from(context).wakeBus }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    val tick by wakeBus.tick.collectAsState()

    // System back walks the WebView history (the SPA creates real entries for
    // thread/compose navigation and seeds a base entry under deep links). At
    // the history root the handler disables itself and default back applies.
    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    // Foreground wake: a tick that arrives while the app is open refreshes the SPA
    // once the page is ready. StateFlow holds the latest tick, so a wake landing
    // before load is applied when pageLoaded flips true (no missed wake). tick starts at 0.
    LaunchedEffect(tick, pageLoaded) {
        if (pageLoaded && tick > 0L) {
            webView?.evaluateJavascript(WAKE_JS, null)
        }
    }

    // Background wake: consumed once on the next resume, after the page is ready.
    // Keyed ONLY on lifecycleOwner — the observer reads pageLoaded/webView live at
    // event time. It must NOT re-key on pageLoaded: that would dispose+recreate the
    // effect on the first page load, and the onDispose below would null the WebView.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pageLoaded && wakeBus.consumePending()) {
                webView?.evaluateJavascript(WAKE_JS, null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Clear the WebView reference only when the composable truly leaves composition.
    DisposableEffect(Unit) {
        onDispose { webView = null }
    }

    AndroidView(factory = { ctx ->
        val bridge = ShellBridge()
        var refreshLayout: SwipeRefreshLayout? = null
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            run {
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
            }
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true

            // One-way SPA → shell signal gating pull-to-refresh (see ShellBridge).
            // Main-frame navigation is pinned to subApp.host by NavPolicy, and the
            // interface carries a single boolean — no data is exposed.
            addJavascriptInterface(bridge, "WwtShell")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    return if (NavPolicy.isInApp(url, subApp.host)) {
                        false // let the WebView load it
                    } else {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: android.content.ActivityNotFoundException) {
                            android.util.Log.w("SubAppWebView", "No app to open external link: $url")
                        }
                        true // handled externally
                    }
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError,
                ) {
                    if (request.isForMainFrame) onPageError()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = true
                    refreshLayout?.isRefreshing = false
                    onPageLoaded()
                }

                override fun doUpdateVisitedHistory(
                    view: WebView, url: String?, isReload: Boolean,
                ) {
                    // Fires for full loads AND the SPA's pushState/hash entries,
                    // keeping the BackHandler's enablement in sync.
                    canGoBack = view.canGoBack()
                }
            }

            // Seed the session cookie from the stored token NOW (the WebView's cookie
            // store is live at this point) so the SPA loads already authenticated.
            // The cookie is committed before loadUrl via the setCookie callback to
            // avoid a race between seeding and the first request.
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (sessionToken != null) {
                cm.setCookie(subApp.url, sessionCookieLine(sessionToken)) {
                    cm.flush()
                    loadUrl(subApp.url)
                }
            } else {
                loadUrl(subApp.url)
            }
        }.also { webView = it }

        SwipeRefreshLayout(ctx).apply {
            refreshLayout = this
            addView(wv)
            // Arm the gesture ONLY when the SPA says its list is visible and at
            // the top ("child can scroll up" everywhere else, so drags scroll).
            setOnChildScrollUpCallback { _, _ -> !bridge.atTop }
            setOnRefreshListener {
                if (pageLoaded) {
                    wv.evaluateJavascript(WAKE_JS, null)
                    postDelayed({ isRefreshing = false }, REFRESH_SPINNER_MS)
                } else {
                    // Page never finished loading — a real reload both refreshes
                    // and recovers; onPageFinished stops the spinner.
                    wv.reload()
                }
            }
        }
    })
}
```

What changed vs the previous version (for the reviewer's orientation):
- `BackHandler` + `canGoBack` state + `doUpdateVisitedHistory` override.
- The factory now returns a `SwipeRefreshLayout` wrapping the WebView; `addJavascriptInterface(bridge, "WwtShell")`; refresh listener + child-scroll gate; `onPageFinished` additionally stops the spinner (covers the reload path; harmless on normal loads).
- Everything else (wake plumbing, cookie seeding, NavPolicy routing, security settings, comments) is byte-identical to before.

- [ ] **Step 7: Full unit suite + debug build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all unit tests pass.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/main/java/tech/whitewolf/app/web/ShellBridge.kt \
  app/src/test/java/tech/whitewolf/app/web/ShellBridgeTest.kt \
  app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt
git commit -m "feat(shell): back-button WebView navigation + pull-to-refresh

System back now walks the WebView history (the SPA creates real
entries since webmail PR #32) and only exits at the root. A
SwipeRefreshLayout wraps the WebView, armed solely when the SPA
reports list-at-top via the new window.WwtShell bridge (webmail
PR #33); refresh fires window.wwtWake()."
```

---

## Manual verification (operator, on-device — not possible in this environment)

1. Open the app → open a thread → system back returns to the list (does not exit); back again from the list exits.
2. At the top of the list, pull down → native spinner → list refreshes (send yourself a mail first to see it appear).
3. Scroll mid-list, drag down → the list scrolls; no spinner.
4. Open compose or a thread → pull down → no spinner.

## Final verification

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` green.
- No instrumented tests added; existing ones untouched.
