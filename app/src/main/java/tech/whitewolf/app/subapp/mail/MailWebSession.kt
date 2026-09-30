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

/**
 * The retained half of a hosted web sub-app: the WebView plus the state its client
 * writes. Retaining a bare WebView does not work — its WebViewClient closures capture
 * composable-local state, so re-attaching into a fresh composition leaves the client
 * writing to a dead composition (canGoBack resets, pull-to-refresh sees pageLoaded=false
 * and reloads). Here the state lives with the session, exposed as StateFlows a
 * composition observes with `collectAsState()` — not a listener callback: MailContent
 * never actually calls a `bind()`, so a prior SessionListener/bind/unbind here was dead
 * production code, exercised only by tests that existed to exercise it.
 */
class MailWebSession(
    /**
     * Public for `buildContainer`'s construction-time access to the concrete view
     * (`(session.web as AndroidWebViewHandle).view`) — narrowing that is a larger change
     * than this class's guard boundary. Every OTHER caller must go through this class's
     * own guarded delegates ([goBack], [evaluateJavascript], and the rest below) rather
     * than reaching through this field directly: a direct `session.web.xxx()` call
     * bypasses [destroyed] entirely, and Finding 4's own fix originally missed exactly
     * that — `MailContent` called `session.web.goBack()` and
     * `session.web.evaluateJavascript(...)` straight through this field, both still
     * reachable in the one-frame window where `destroy()` has run but the composition
     * showing this session has not yet disposed.
     */
    val web: WebViewHandle,
) {
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

    /**
     * True once [destroy] has run. Sign-out's `discardAll()` calls [destroy] SYNCHRONOUSLY
     * on the UI thread, but Compose's own disposal of the composition that was showing
     * this session (MailContent's `DisposableEffect` -> [onDetached]) is not ordered
     * against that call — it lands on a later recomposition, which can be AFTER `destroy()`
     * already ran `web.destroy()`. Android documents further calls on a destroyed WebView
     * as undefined behaviour (observed in practice as "Called on a destroyed WebView",
     * sometimes throwing), so every method below that forwards to [web] must become a
     * no-op once this is true — a destroyed session must be inert, not merely
     * un-reattached.
     */
    private var destroyed = false

    /** Re-entering composition: prime from the live view, resume timers. */
    fun onAttached() {
        if (destroyed) return
        _canGoBack.value = web.canGoBack()
        web.onResume()
    }

    /** Leaving composition (launcher, another sub-app): stop JS timers. */
    fun onDetached() {
        if (destroyed) return
        web.onPause()
    }

    fun notifyPageFinished() {
        _pageLoaded.value = true
        _errored.value = false
    }

    fun notifyHistoryChanged() {
        if (destroyed) return
        _canGoBack.value = web.canGoBack()
    }

    fun notifyMainFrameError() {
        _errored.value = true
    }

    /** System back, gated the same way every other [web]-forwarding method is: see
     *  [destroyed]. MailContent's BackHandler must call this, not `session.web.goBack()`
     *  directly — the latter bypasses the guard entirely. */
    fun goBack() {
        if (destroyed) return
        web.goBack()
    }

    /** A wake tick's JS ping into the page, gated the same way every other
     *  [web]-forwarding method is: see [destroyed]. MailContent's wake effect must call
     *  this, not `session.web.evaluateJavascript(...)` directly — the latter bypasses
     *  the guard entirely. */
    fun evaluateJavascript(script: String) {
        if (destroyed) return
        web.evaluateJavascript(script)
    }

    /**
     * Retry after a load failure: clears [errored] optimistically, before reloading,
     * rather than waiting for [notifyPageFinished]/[notifyMainFrameError] to report back.
     * A REPEAT failure then sets `_errored` true->false->true rather than true->true, so a
     * Compose effect keyed on `errored` (MailContent's auto-retry) sees a real transition
     * and restarts instead of silently doing nothing because the StateFlow never changed.
     */
    fun retry() {
        if (destroyed) return
        _errored.value = false
        web.reload()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        web.destroy()
    }
}
