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

    @Test fun noDistributorTextMentionsObtainiumAndHost() {
        val text = pushBannerText(PushStatus.NoDistributor)
        assertEquals(
            "Notifications are off. Install the ntfy app via Obtainium and set its " +
                "server to ntfy.whitewolf.tech.",
            text,
        )
    }

    @Test fun wrongServerTextNamesTheWrongHost() {
        val text = pushBannerText(PushStatus.WrongServer("ntfy.sh"))
        assertTrue(text!!.contains("ntfy.sh"))
        assertTrue(text.contains("ntfy.whitewolf.tech"))
    }
}
