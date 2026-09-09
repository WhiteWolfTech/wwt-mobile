package tech.whitewolf.app.subapp.mail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The slice of WebView this session drives. An interface so the session is JVM-testable. */
interface WebViewHandle {
    fun canGoBack(): Boolean
    fun goBack()
    fun reload()
    fun loadUrl(url: String)
    fun evaluateJavascript(script: String)
    fun onPause()
    fun onResume()
    fun destroy()
}

/** Callbacks a composition binds while it is on screen. */
interface SessionListener {
    fun onPageFinished()
    fun onHistoryChanged(canGoBack: Boolean)
    fun onMainFrameError()
}

/**
 * The retained half of a hosted web sub-app: the WebView plus the state its client
 * writes. Retaining a bare WebView does not work — its WebViewClient closures capture
 * composable-local state, so re-attaching into a fresh composition leaves the client
 * writing to a dead composition (canGoBack resets, pull-to-refresh sees pageLoaded=false
 * and reloads). Here the state lives with the session and the listener is rebound.
 */
class MailWebSession(val web: WebViewHandle) {
    /**
     * The view actually handed to AndroidView. Retained here so a second composition
     * re-attaches the same one; the factory must detach it from its previous parent first.
     * Set by the composable that builds it (Task 8).
     */
    var container: android.view.ViewGroup? = null

    /**
     * The session token whose cookie is currently seeded, and the highest wake tick already
     * applied. Both live HERE rather than in composition state: a `remember`-scoped copy
     * resets on every launcher round-trip, which would reload the SPA and re-fire wakes —
     * defeating the retention this class exists to provide.
     */
    var seededToken: String? = null
    var lastWakeSeen: Long = 0L

    private val _pageLoaded = MutableStateFlow(false)
    val pageLoaded: StateFlow<Boolean> = _pageLoaded

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val _errored = MutableStateFlow(false)
    val errored: StateFlow<Boolean> = _errored

    @Volatile private var listener: SessionListener? = null

    fun bind(l: SessionListener) { listener = l }

    fun unbind() { listener = null }

    /** Re-entering composition: prime from the live view, resume timers. */
    fun onAttached() {
        _canGoBack.value = web.canGoBack()
        web.onResume()
    }

    /** Leaving composition (launcher, another sub-app): stop JS timers. */
    fun onDetached() {
        web.onPause()
    }

    fun notifyPageFinished() {
        _pageLoaded.value = true
        _errored.value = false
        listener?.onPageFinished()
    }

    fun notifyHistoryChanged() {
        val v = web.canGoBack()
        _canGoBack.value = v
        listener?.onHistoryChanged(v)
    }

    fun notifyMainFrameError() {
        _errored.value = true
        listener?.onMainFrameError()
    }

    fun destroy() {
        unbind()
        web.destroy()
    }
}
