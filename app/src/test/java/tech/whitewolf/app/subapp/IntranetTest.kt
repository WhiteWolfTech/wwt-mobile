package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntranetTest {
    @Test fun urlComesFromBuildConfig() {
        // Configured, not a literal — the same contract SubAppRegistry keeps for mail.
        assertTrue(Intranet.url.startsWith("https://"))
    }

    @Test fun intranetIsNotTheMailSubApp() {
        // The whole point of the WWT action is that it leaves mail. Wiring
        // INTRANET_URL to the mail backend (an easy copy-paste) would make the
        // button a no-op that reloads the app the user is already in.
        assertNotEquals(SubAppRegistry.default().url, Intranet.url)
    }

    @Test fun exposesHostForNavPolicy() {
        // NavPolicy pins the WebView to the mail host, so the intranet host must be
        // a different one — that difference is what sends it to an external browser
        // instead of loading it over the mailbox.
        assertNotEquals(SubAppRegistry.default().host, Intranet.host)
        assertEquals(java.net.URI(Intranet.url).host, Intranet.host)
    }
}
