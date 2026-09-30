package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId
import org.junit.Assert.assertEquals
import org.junit.Test

private val mail = SubAppId("mail")
private val video = SubAppId("video")

class WakeBusTest {
    @Test fun tickStartsAtZero() {
        assertEquals(0L, WakeBus().tick(mail).value)
    }

    @Test fun signallingOneSubAppDoesNotDisturbAnother() {
        val bus = WakeBus()
        bus.signal(video)
        assertEquals(1L, bus.tick(video).value)
        assertEquals(0L, bus.tick(mail).value)
    }

    @Test fun theSameFlowInstanceIsReturnedPerSubApp() {
        val bus = WakeBus()
        val first = bus.tick(mail)
        bus.signal(mail)
        assertEquals(1L, first.value)
    }

    @Test fun aWakeThatAlsoNotifiedStillBumpsTheTick() {
        // The old design set `pending` here and left the tick alone, so walking
        // launcher -> mail (no ON_RESUME) showed a stale inbox.
        val bus = WakeBus()
        bus.signal(mail)
        assertEquals(1L, bus.tick(mail).value)
    }

    @Test fun notifyUnlessTheTargetSubAppIsVisible() {
        assertEquals(WakeAction.Foreground, wakeAction(appForeground = true, targetIsVisible = true))
        // App is open, but the user is in a DIFFERENT sub-app: a silent refresh would be
        // invisible and they would never learn mail arrived.
        assertEquals(WakeAction.Background, wakeAction(appForeground = true, targetIsVisible = false))
        assertEquals(WakeAction.Background, wakeAction(appForeground = false, targetIsVisible = false))
        assertEquals(WakeAction.Background, wakeAction(appForeground = false, targetIsVisible = true))
    }
}
