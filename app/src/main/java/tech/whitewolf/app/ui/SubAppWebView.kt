package tech.whitewolf.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import tech.whitewolf.app.subapp.SubApp
import tech.whitewolf.app.web.NavPolicy

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SubAppWebView(subApp: SubApp, onPageError: () -> Unit, onPageLoaded: () -> Unit) {
    val context = LocalContext.current
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

                override fun onPageFinished(view: WebView, url: String) { onPageLoaded() }
            }
            loadUrl(subApp.url)
        }
    })
}
