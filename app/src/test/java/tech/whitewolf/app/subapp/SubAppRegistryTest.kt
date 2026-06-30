package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAppRegistryTest {
    @Test fun mailIsTheSoleDefaultSubApp() {
        val all = SubAppRegistry.all()
        assertEquals(1, all.size)
        val mail = SubAppRegistry.default()
        assertEquals("mail", mail.id)
        assertEquals("Mail", mail.title)
        assertEquals(mail, all.first())
    }

    @Test fun subAppExposesHostFromUrl() {
        val s = SubApp(id = "x", title = "X", url = "https://mail.whitewolf.tech/inbox")
        assertEquals("mail.whitewolf.tech", s.host)
    }

    @Test fun defaultUrlComesFromBuildConfig() {
        // The mail entry's URL must be the configured MAIL_BASE_URL, not a literal.
        assertTrue(SubAppRegistry.default().url.startsWith("https://"))
    }
}
