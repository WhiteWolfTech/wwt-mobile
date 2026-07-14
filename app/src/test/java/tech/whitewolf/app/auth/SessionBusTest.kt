package tech.whitewolf.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionBusTest {
    @Test fun startsFromTheStoredSessionAndExplainsNothing() {
        assertTrue(SessionBus(initial = true).loggedIn.value)
        assertFalse(SessionBus(initial = true).invalidated.value)
        assertFalse(SessionBus(initial = false).loggedIn.value)
    }

    @Test fun serverRejectionSignsOutAndRaisesTheNotice() {
        val bus = SessionBus(initial = true)
        bus.invalidate()
        assertFalse(bus.loggedIn.value)
        assertTrue(bus.invalidated.value)
    }

    @Test fun deliberateSignOutRaisesNoNotice() {
        val bus = SessionBus(initial = true)
        bus.signedOut()
        assertFalse(bus.loggedIn.value)
        assertFalse(bus.invalidated.value)
    }

    /** The teardown of a deliberate sign-out uses the live bearer and can itself earn a
     *  401. The user chose to leave — they must not be told their session expired. */
    @Test fun a401FromOurOwnSignOutTeardownIsNotSessionExpiry() {
        val bus = SessionBus(initial = true)
        bus.beginSignOut()
        bus.invalidate() // the unregister call 401s mid-teardown
        assertFalse(bus.invalidated.value)
        bus.endSignOut()
    }

    @Test fun a401AfterTheSignOutFinishesIsRealAgain() {
        val bus = SessionBus(initial = true)
        bus.beginSignOut()
        bus.endSignOut()
        bus.invalidate()
        assertTrue(bus.invalidated.value)
    }

    @Test fun loggingBackInClearsTheNotice() {
        val bus = SessionBus(initial = false)
        bus.invalidate()
        assertTrue(bus.invalidated.value)

        bus.signedIn()
        assertTrue(bus.loggedIn.value)
        assertFalse(bus.invalidated.value)
    }

    /**
     * invalidate() races beginSignOut(): the push thread can 401 at the very moment the
     * user hits Sign out. Only two interleavings are possible, and both must end with the
     * notice down — invalidate() first, and beginSignOut() clears it; or beginSignOut()
     * first, and the gate suppresses it.
     *
     * This is the test that fails without @Synchronized: invalidate() could read
     * signingOut == false, beginSignOut() could then clear the flag, and invalidate()
     * could then set it — leaving "Your session expired" on the screen after a sign-out
     * the user asked for.
     */
    @Test fun a401RacingSignOutNeverLeavesTheNoticeRaised() {
        repeat(500) {
            val bus = SessionBus(initial = true)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            Thread { start.await(); bus.invalidate(); done.countDown() }.start()
            Thread { start.await(); bus.beginSignOut(); done.countDown() }.start()

            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))

            assertFalse(
                "a 401 racing a deliberate sign-out must not leave the notice raised",
                bus.invalidated.value,
            )
        }
    }
}
