package tech.whitewolf.app.subapp.mail

import android.webkit.WebView

/**
 * [WebViewHandle] over a real [WebView]. `view` is exposed so the compose layer
 * (MailContent's `buildContainer`) can reach the Android-specific surface a
 * [WebViewHandle] deliberately doesn't carry — settings, `webViewClient`, the JS
 * interface, and adding it to a view tree.
 */
class AndroidWebViewHandle(val view: WebView) : WebViewHandle {
    override fun canGoBack() = view.canGoBack()
    override fun goBack() = view.goBack()
    override fun reload() = view.reload()
    override fun loadUrl(url: String) = view.loadUrl(url)
    override fun evaluateJavascript(script: String) = view.evaluateJavascript(script, null)
    override fun onPause() = view.onPause()
    override fun onResume() = view.onResume()
    override fun destroy() = view.destroy()
}
