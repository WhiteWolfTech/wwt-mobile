package tech.whitewolf.app.subapp.mail

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
    // Records call order so a test can tell onPause/onResume apart from a backwards
    // implementation that wires them to the wrong lifecycle method — matching totals
    // alone can't catch that.
    val calls = mutableListOf<String>()
    override fun canGoBack() = history
    override fun goBack() {}
    override fun reload() { reloads++ }
    override fun loadUrl(url: String) { loaded = url }
    override fun evaluateJavascript(script: String) { js += script }
    override fun onPause() { paused++; calls += "pause" }
    override fun onResume() { resumed++; calls += "resume" }
    override fun destroy() { destroyed = true }
}

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
        s.bind(object : SessionListener {
            override fun onPageFinished() {}
            override fun onHistoryChanged(canGoBack: Boolean) {}
            override fun onMainFrameError() {}
        })
        s.notifyPageFinished()
        s.unbind()
        s.onDetached()
        s.onAttached()
        assertTrue(s.pageLoaded.value)
    }

    @Test fun anUnboundListenerReceivesNothing() {
        var finishes = 0
        val s = MailWebSession(FakeWeb())
        val l = object : SessionListener {
            override fun onPageFinished() { finishes++ }
            override fun onHistoryChanged(canGoBack: Boolean) {}
            override fun onMainFrameError() {}
        }
        s.bind(l)
        s.notifyPageFinished()
        s.unbind()
        s.notifyPageFinished()
        assertEquals(1, finishes)
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

    @Test fun historyChangeUpdatesCanGoBackAndNotifiesTheBoundListener() {
        // notifyHistoryChanged() has two effects — updating the canGoBack flow and firing
        // the listener. A test that only checks one lets an implementation that drops the
        // other slip through.
        val web = FakeWeb(history = false)
        val s = MailWebSession(web)
        var notified: Boolean? = null
        s.bind(object : SessionListener {
            override fun onPageFinished() {}
            override fun onHistoryChanged(canGoBack: Boolean) { notified = canGoBack }
            override fun onMainFrameError() {}
        })
        assertFalse(s.canGoBack.value)

        web.history = true
        s.notifyHistoryChanged()

        assertTrue(s.canGoBack.value)
        assertEquals(true, notified)
    }

    @Test fun mainFrameErrorNotifiesTheBoundListener() {
        // errorIsRecordedSoTheHostCanSuppressHistoryBack never binds a listener, so it can't
        // catch a notifyMainFrameError() that forgot to call listener?.onMainFrameError().
        val s = MailWebSession(FakeWeb())
        var errors = 0
        s.bind(object : SessionListener {
            override fun onPageFinished() {}
            override fun onHistoryChanged(canGoBack: Boolean) {}
            override fun onMainFrameError() { errors++ }
        })
        s.notifyMainFrameError()
        assertEquals(1, errors)
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
}
