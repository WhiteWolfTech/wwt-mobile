package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeBusTest {
    @Test fun tickStartsAtZero() {
        assertEquals(0L, WakeBus().tick.value)
    }

    @Test fun foregroundWakeIncrementsTickAndSetsNoPending() {
        val bus = WakeBus()
        bus.signalWakeForeground()
        assertEquals(1L, bus.tick.value)
        assertFalse(bus.consumePending())
    }

    @Test fun backgroundWakeSetsPendingAndDoesNotBumpTick() {
        val bus = WakeBus()
        bus.signalWakeBackground()
        assertEquals(0L, bus.tick.value)
        assertTrue(bus.consumePending())
    }

    @Test fun consumePendingClearsAfterFirstRead() {
        val bus = WakeBus()
        bus.signalWakeBackground()
        assertTrue(bus.consumePending())
        assertFalse(bus.consumePending())
    }

    @Test fun wakeActionMapsForegroundFlag() {
        assertEquals(WakeAction.Foreground, wakeAction(true))
        assertEquals(WakeAction.Background, wakeAction(false))
    }
}
