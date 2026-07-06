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
