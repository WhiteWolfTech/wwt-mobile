package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionNoticeTest {
    @Test fun noNoticeWhenTheSessionWasNotInvalidated() {
        assertNull(sessionNoticeFor(invalidated = false))
    }

    @Test fun noticeExplainsAnInvoluntaryLogout() {
        assertEquals(
            "Your session expired. Please sign in again.",
            sessionNoticeFor(invalidated = true),
        )
    }
}
