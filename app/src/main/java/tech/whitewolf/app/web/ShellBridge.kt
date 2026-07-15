package tech.whitewolf.app.web

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.whitewolf.app.BuildConfig

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

    /**
     * Shell build identity for the web app to display (e.g. its Settings), as JSON:
     * {"version","code","commit"}. Called synchronously from JS as
     * `window.WwtShell.appInfo()`. Present only inside the shell, so the SPA can
     * feature-detect it and fall back to its own build info on the plain web.
     */
    @JavascriptInterface
    fun appInfo(): String =
        buildAppInfoJson(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA)
}

/**
 * Serialises the shell's build identity into a compact JSON object for the web app to
 * display (e.g. in its Settings). Uses kotlinx.serialization so values are always JSON-
 * escaped rather than string-concatenated. Shape: {"version","code","commit"}.
 */
internal fun buildAppInfoJson(version: String, code: Int, commit: String): String =
    buildJsonObject {
        put("version", version)
        put("code", code)
        put("commit", commit)
    }.toString()
