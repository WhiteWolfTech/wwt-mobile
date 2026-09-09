package tech.whitewolf.app.subapp.mail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeWeb(var history: Boolean = false) : WebViewHandle {
    var reloads = 0
    var loaded: String? = null
    val js = mutableListOf<String>()
    var paused = 0
    var resumed = 0
    var destroyed = false
    var destroys = 0
    var goBacks = 0
    // Records call order so a test can tell onPause/onResume apart from a backwards
    // implementation that wires them to the wrong lifecycle method — matching totals
    // alone can't catch that.
    val calls = mutableListOf<String>()
    // Fires from inside reload(), letting a test simulate the reload itself failing —
    // e.g. a repeat main-frame error — so ordering around reload() is observable.
    var onReload: (() -> Unit)? = null
    override fun canGoBack() = history
    override fun goBack() { goBacks++ }
    override fun reload() { reloads++; onReload?.invoke() }
    override fun loadUrl(url: String) { loaded = url }
    override fun evaluateJavascript(script: String) { js += script }
    override fun onPause() { paused++; calls += "pause" }
    override fun onResume() { resumed++; calls += "resume" }
    override fun destroy() { destroyed = true; destroys++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailWebSessionTest {
    @Test fun attachPrimesCanGoBackFromTheLiveWebView() {
        // The retained client keeps browsing history across a launcher round-trip, but a
        // fresh composition starts at canGoBack=false. Without priming, back would leave
        // the sub-app mid-history.
        val web = FakeWeb(history = true)
        val s = MailWebSession(web)
        assertFalse(s.canGoBack.value)
        s.onAttached()
        assertTrue(s.canGoBack.value)
    }

    @Test fun attachDoesNotResetPageLoaded() {
        // Pull-to-refresh reads pageLoaded; if re-attach reset it, refresh would call
        // reload() — the exact reload retention exists to prevent.
        val s = MailWebSession(FakeWeb())
        s.notifyPageFinished()
        s.onDetached()
        s.onAttached()
        assertTrue(s.pageLoaded.value)
    }

    @Test fun detachPausesAndAttachResumesTheWebView() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.onDetached()
        s.onAttached()
        assertEquals(1, web.paused)
        assertEquals(1, web.resumed)
        // Totals alone pass a backwards wiring (onDetached->resume, onAttached->pause).
        // Only the sequence proves onDetached paused and onAttached resumed, in that order.
        assertEquals(listOf("pause", "resume"), web.calls)
    }

    @Test fun errorIsRecordedSoTheHostCanSuppressHistoryBack() {
        val s = MailWebSession(FakeWeb(history = true))
        s.notifyMainFrameError()
        assertTrue(s.errored.value)
    }

    @Test fun historyChangeUpdatesCanGoBack() {
        // The BackHandler in MailContent reads canGoBack via collectAsState(); this pins
        // that notifyHistoryChanged() (fired from doUpdateVisitedHistory) keeps it in sync.
        val web = FakeWeb(history = false)
        val s = MailWebSession(web)
        assertFalse(s.canGoBack.value)

        web.history = true
        s.notifyHistoryChanged()

        assertTrue(s.canGoBack.value)
    }

    @Test fun pageFinishedClearsAPriorError() {
        // An error screen must clear once a later load succeeds; notifyPageFinished() is
        // where that recovery happens.
        val s = MailWebSession(FakeWeb())
        s.notifyMainFrameError()
        assertTrue(s.errored.value)

        s.notifyPageFinished()

        assertFalse(s.errored.value)
    }

    @Test fun retryClearsTheErrorBeforeReloadingSoARepeatFailureIsObservable() = runTest(UnconfinedTestDispatcher()) {
        // Pins the ORDER inside retry(), not just its end state: clear `errored` BEFORE
        // calling reload(), not after. A test that only checks the final value (as the
        // first version of this test did) cannot tell the two orders apart when reload()
        // is a bare counter that never calls back — both orders end up false. So make
        // reload() itself simulate an IMMEDIATE repeat failure, and collect the actual
        // emission SEQUENCE on `errored` (UnconfinedTestDispatcher so each StateFlow
        // write resumes the collector synchronously, in order, rather than coalescing
        // multiple writes into one final value the way a StandardTestDispatcher would).
        //
        // Correct order (clear, then reload-which-fails): true (initial failure) ->
        // false (retry's optimistic clear) -> true (repeat failure — a REAL false->true
        // transition, since errored was false when it landed).
        // Wrong order (reload-which-fails, then clear): true (initial failure) -> the
        // repeat failure's `_errored.value = true` writes an EQUAL value while still
        // true, MutableStateFlow dedupes it away, nothing observable happens -> false
        // (the trailing clear) — landing on `false` while a load is still actually
        // broken, and one fewer emission than the correct order produced.
        val web = FakeWeb()
        val s = MailWebSession(web)
        web.onReload = { s.notifyMainFrameError() }
        s.notifyMainFrameError()

        val emissions = mutableListOf<Boolean>()
        val job = launch { s.errored.collect { emissions.add(it) } }

        s.retry()

        job.cancel()
        assertEquals(listOf(true, false, true), emissions)
        // The end state matters too: a wrong order would land on `false` (a live error
        // wrongly cleared) instead of `true` (the repeat failure correctly still showing).
        assertTrue(s.errored.value)
        assertEquals(1, web.reloads)
    }

    // Sign-out's discardAll() calls destroy() synchronously on the UI thread, but
    // Compose's own disposal of the composition that was showing this session
    // (MailContent's DisposableEffect -> onDetached()) is not ordered against that —
    // it can land on a later recomposition, AFTER destroy() already tore down the
    // WebView. Android treats further calls on a destroyed WebView as undefined
    // behaviour (observed as "Called on a destroyed WebView", sometimes throwing), so
    // a destroyed session must not forward onAttached()/onDetached()/retry() to it.

    @Test fun destroyedSessionIgnoresOnDetached() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.destroy()
        val pausedBefore = web.paused
        s.onDetached()
        assertEquals(pausedBefore, web.paused)
    }

    @Test fun destroyedSessionIgnoresOnAttached() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.destroy()
        val resumedBefore = web.resumed
        s.onAttached()
        assertEquals(resumedBefore, web.resumed)
    }

    @Test fun destroyCalledTwiceDestroysTheWebViewOnce() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.destroy()
        s.destroy()
        assertEquals(1, web.destroys)
    }

    // Finding 4 follow-up: MailContent used to reach through `session.web` directly for
    // back-press (`session.web.goBack()`) and the wake tick's JS ping
    // (`session.web.evaluateJavascript(...)`), bypassing `destroyed` entirely — both
    // reachable in the one-frame window between destroy() and the old composition's
    // disposal. MailWebSession now owns guarded delegates for both; these pin that they
    // no-op once destroyed, same shape as the pause/resume tests above.

    @Test fun destroyedSessionIgnoresGoBack() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.destroy()
        s.goBack()
        assertEquals(0, web.goBacks)
    }

    @Test fun destroyedSessionIgnoresEvaluateJavascript() {
        val web = FakeWeb()
        val s = MailWebSession(web)
        s.destroy()
        s.evaluateJavascript("window.wwtWake && window.wwtWake()")
        assertTrue(web.js.isEmpty())
    }
}
