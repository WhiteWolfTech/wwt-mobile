package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
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
}
