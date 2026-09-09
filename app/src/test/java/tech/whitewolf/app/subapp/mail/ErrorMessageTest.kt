package tech.whitewolf.app.subapp.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ErrorMessageTest {
    @Test
    fun `online shows the server-unreachable copy`() {
        assertEquals("Couldn't reach Mail.", errorMessageFor(online = true, title = "Mail"))
    }

    @Test
    fun `offline shows the waiting-for-connection copy`() {
        assertEquals("You're offline. Waiting for a connection…", errorMessageFor(online = false, title = "Mail"))
    }

    @Test fun anErroredSessionSuppressesHistoryBack() {
        // canGoBack can be true while the error screen shows — a main-frame error on a
        // later navigation leaves earlier history intact. Back must reach the launcher,
        // not walk history behind an error screen.
        val session = MailWebSession(object : WebViewHandle {
            override fun canGoBack() = true
            override fun goBack() {}
            override fun reload() {}
            override fun loadUrl(url: String) {}
            override fun evaluateJavascript(js: String) {}
            override fun onPause() {}
            override fun onResume() {}
            override fun destroy() {}
        })
        session.onAttached()
        session.notifyMainFrameError()
        assertFalse(mailBackEnabled(session.canGoBack.value, session.errored.value))
    }
}
