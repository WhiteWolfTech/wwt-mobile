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
    override fun canGoBack() = history
    override fun goBack() {}
    override fun reload() { reloads++ }
    override fun loadUrl(url: String) { loaded = url }
    override fun evaluateJavascript(script: String) { js += script }
    override fun onPause() { paused++ }
    override fun onResume() { resumed++ }
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
    }

    @Test fun errorIsRecordedSoTheHostCanSuppressHistoryBack() {
        val s = MailWebSession(FakeWeb(history = true))
        s.notifyMainFrameError()
        assertTrue(s.errored.value)
    }
}
