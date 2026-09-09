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

private class ThrowingThing(val tag: String) : Retained {
    var discarded = false
    override fun onDiscard() {
        discarded = true
        throw RuntimeException("Simulated cleanup failure")
    }
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
        // Verify the map was actually cleared, not just onDiscard() called.
        // A buggy implementation that forgets held.clear() would pass the above
        // but still leave the scope vulnerable to reattachment.
        val newMail = s.getOrPut(SubAppId("mail")) { Thing("fresh") }
        val newVideo = s.getOrPut(SubAppId("video")) { Thing("fresh") }
        assertNotSame(mail, newMail)
        assertNotSame(video, newVideo)
        assertEquals("fresh", newMail.tag)
        assertEquals("fresh", newVideo.tag)
    }

    @Test fun discardAllIsExceptionSafe() {
        // If one item throws during cleanup, the next must still be discarded
        // and the map must be empty. This is a security requirement.
        val s = SubAppScopes()
        val first = s.getOrPut(SubAppId("mail")) { ThrowingThing("mail") }
        val second = s.getOrPut(SubAppId("video")) { Thing("video") }
        s.discardAll()
        // Both were asked to discard.
        assertTrue(first.discarded)
        assertTrue(second.discarded)
        // Map was cleared despite the throw.
        val newMail = s.getOrPut(SubAppId("mail")) { Thing("fresh") }
        assertEquals("fresh", newMail.tag)
    }

    @Test fun discardIsExceptionSafe() {
        // Throwing onDiscard() should not propagate; the entry must still be removed.
        val s = SubAppScopes()
        val throwing = s.getOrPut(SubAppId("mail")) { ThrowingThing("mail") }
        s.discard(SubAppId("mail"))
        assertTrue(throwing.discarded)
        // Entry was removed, so next getOrPut creates fresh.
        val fresh = s.getOrPut(SubAppId("mail")) { Thing("fresh") }
        assertNotSame(throwing, fresh)
        assertEquals("fresh", fresh.tag)
    }
}
