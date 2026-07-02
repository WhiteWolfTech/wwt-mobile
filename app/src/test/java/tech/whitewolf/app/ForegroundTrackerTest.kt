package tech.whitewolf.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    @Test fun startsInBackground() {
        assertFalse(ForegroundTracker().isForeground)
    }

    @Test fun oneStartedActivityIsForeground() {
        val t = ForegroundTracker()
        t.onStart()
        assertTrue(t.isForeground)
    }

    @Test fun backgroundOnceAllActivitiesStopped() {
        val t = ForegroundTracker()
        t.onStart(); t.onStart()
        t.onStop()
        assertTrue(t.isForeground)
        t.onStop()
        assertFalse(t.isForeground)
    }
}
