package tech.whitewolf.app.subapp.mail

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared link-routing decision behind both the main client and popups (WWT-219). */
class OpenExternallyTest {
    private val host = "mail.whitewolf.tech"

    @Test fun inAppUrlIsLeftToTheWebView() {
        val launched = mutableListOf<String>()
        assertFalse(openExternally("https://mail.whitewolf.tech/inbox", host) { launched += it })
        assertTrue(launched.isEmpty())
    }

    @Test fun externalUrlIsLaunchedAndHandled() {
        val launched = mutableListOf<String>()
        assertTrue(openExternally("https://example.com/a", host) { launched += it })
        assertEquals(listOf("https://example.com/a"), launched)
    }

    @Test fun mailtoIsLaunched() {
        val launched = mutableListOf<String>()
        assertTrue(openExternally("mailto:a@b.com", host) { launched += it })
        assertEquals(listOf("mailto:a@b.com"), launched)
    }

    @Test fun missingHandlerStillCountsAsHandled() {
        // The WebView must not fall back to loading a foreign page itself.
        assertTrue(openExternally("https://example.com/a", host) { throw ActivityNotFoundException() })
    }
}
