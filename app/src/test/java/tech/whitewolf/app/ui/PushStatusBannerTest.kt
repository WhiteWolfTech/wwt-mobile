package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okHasNoBannerText() {
        assertNull(pushBannerText(PushStatus.Ok))
    }

    @Test fun noDistributorTextIntroducesTheInstallUrl() {
        assertEquals(
            "Notifications are off. In Obtainium, add this app source, then set the " +
                "ntfy server to ntfy.whitewolf.tech:",
            pushBannerText(PushStatus.NoDistributor),
        )
    }

    @Test fun wrongServerTextNamesTheWrongHost() {
        val text = pushBannerText(PushStatus.WrongServer("ntfy.sh"))
        assertTrue(text!!.contains("ntfy.sh"))
        assertTrue(text.contains("ntfy.whitewolf.tech"))
    }

    @Test fun installUrlIsTheNtfyRepo() {
        assertEquals("https://github.com/binwiederhier/ntfy", NTFY_INSTALL_URL)
    }
}
