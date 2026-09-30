package tech.whitewolf.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushHealthTest {
    @Test fun noBlockedChannelsIsHealthy() {
        assertFalse(channelsBlocked(blocked = emptySet(), registered = listOf("mail", "video")))
    }

    @Test fun aBlockedChannelForARegisteredSubAppIsAProblem() {
        assertTrue(channelsBlocked(blocked = setOf("video"), registered = listOf("mail", "video")))
    }

    @Test fun aBlockedChannelForNoLongerRegisteredSubAppIsIgnored() {
        // A channel left behind by an uninstalled sub-app must not raise a permanent banner.
        assertFalse(channelsBlocked(blocked = setOf("legacy"), registered = listOf("mail")))
    }
}
