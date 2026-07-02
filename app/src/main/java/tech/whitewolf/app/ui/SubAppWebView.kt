package tech.whitewolf.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.auth.sessionCookieLine
import tech.whitewolf.app.subapp.SubApp
import tech.whitewolf.app.web.NavPolicy

private const val WAKE_JS = "window.wwtWake && window.wwtWake()"

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
    val tick by wakeBus.tick.collectAsState()

    // Foreground wake: a tick that arrives while the app is open refreshes the SPA
    // once the page is ready. StateFlow holds the latest tick, so a wake landing
    // before load is applied when pageLoaded flips true (no missed wake). tick starts at 0.
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

    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
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
                    onPageLoaded()
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
    })
}
