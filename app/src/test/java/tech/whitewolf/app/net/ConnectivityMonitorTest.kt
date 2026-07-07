package tech.whitewolf.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityMonitorTest {
    @Test
    fun `online requires both internet and validated capabilities`() {
        assertTrue(ConnectivityMonitor.isOnline(hasInternet = true, hasValidated = true))
        assertFalse(ConnectivityMonitor.isOnline(hasInternet = true, hasValidated = false))
        assertFalse(ConnectivityMonitor.isOnline(hasInternet = false, hasValidated = true))
        assertFalse(ConnectivityMonitor.isOnline(hasInternet = false, hasValidated = false))
    }
}
