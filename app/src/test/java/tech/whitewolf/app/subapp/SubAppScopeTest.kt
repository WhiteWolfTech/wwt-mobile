package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class Thing(val tag: String) : Retained {
    var discarded = false
    override fun onDiscard() { discarded = true }
}

class SubAppScopeTest {
    @Test fun theSameInstanceIsReturnedAcrossRoundTrips() {
        val s = SubAppScopes()
        val a = s.getOrPut(SubAppId("mail")) { Thing("a") }
        val b = s.getOrPut(SubAppId("mail")) { Thing("b") }
        assertSame(a, b)
    }

    @Test fun subAppsDoNotShareRetainedState() {
        val s = SubAppScopes()
        val mail = s.getOrPut(SubAppId("mail")) { Thing("mail") }
        val video = s.getOrPut(SubAppId("video")) { Thing("video") }
        assertNotSame(mail, video)
    }

    @Test fun discardCallsOnDiscardAndForcesRecreation() {
        val s = SubAppScopes()
        val first = s.getOrPut(SubAppId("mail")) { Thing("first") }
        s.discard(SubAppId("mail"))
        assertTrue(first.discarded)
        val second = s.getOrPut(SubAppId("mail")) { Thing("second") }
        assertEquals("second", second.tag)
    }

    @Test fun discardAllDiscardsEverySubApp() {
        // Sign-out must not leave the previous user's DOM attachable.
        val s = SubAppScopes()
        val mail = s.getOrPut(SubAppId("mail")) { Thing("mail") }
        val video = s.getOrPut(SubAppId("video")) { Thing("video") }
        s.discardAll()
        assertTrue(mail.discarded)
        assertTrue(video.discarded)
    }
}
