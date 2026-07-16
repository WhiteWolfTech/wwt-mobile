package tech.whitewolf.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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

            // Honor system dark mode: when the host DayNight theme is dark, let the
            // WebView report `prefers-color-scheme: dark` so the (dark-aware) SPA
            // themes itself rather than staying light. Feature-guarded — without
            // support the page simply renders light. (WWT-91)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }

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
            // Explicit MATCH_PARENT params: without them the WebView is added
            // with default WRAP_CONTENT layout params, and Chromium then sizes
            // the page's CSS layout viewport to 0px tall (vh/dvh/100% all
            // collapse) even though the view itself is drawn full-size — the
            // 2026-07-07 blank-app incident.
            addView(
                wv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
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
