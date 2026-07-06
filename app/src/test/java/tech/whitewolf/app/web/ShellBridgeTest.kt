package tech.whitewolf.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellBridgeTest {
    @Test
    fun `atTop defaults to false so the gesture never arms without SPA reports`() {
        assertFalse(ShellBridge().atTop)
    }

    @Test
    fun `setAtTop updates the flag both ways`() {
        val bridge = ShellBridge()
        bridge.setAtTop(true)
        assertTrue(bridge.atTop)
        bridge.setAtTop(false)
        assertFalse(bridge.atTop)
    }

    @Test
    fun `value written on another thread is visible to the reader`() {
        val bridge = ShellBridge()
        val t = Thread { bridge.setAtTop(true) }
        t.start()
        t.join()
        assertTrue(bridge.atTop)
    }
}
