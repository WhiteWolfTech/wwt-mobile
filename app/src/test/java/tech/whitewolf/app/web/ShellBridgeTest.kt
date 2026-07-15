package tech.whitewolf.app.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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

    @Test
    fun `appInfo json carries the version name, build code, and commit`() {
        val obj = Json.parseToJsonElement(buildAppInfoJson("0.5.0", 500, "e624edc")).jsonObject
        assertEquals("0.5.0", obj["version"]!!.jsonPrimitive.content)
        assertEquals(500, obj["code"]!!.jsonPrimitive.int)
        assertEquals("e624edc", obj["commit"]!!.jsonPrimitive.content)
    }

    @Test
    fun `appInfo json escapes special characters so it stays parseable`() {
        // A naive string-concat build would produce invalid JSON here.
        val obj = Json.parseToJsonElement(buildAppInfoJson("1.0.0", 1, "a\"b\\c")).jsonObject
        assertEquals("a\"b\\c", obj["commit"]!!.jsonPrimitive.content)
    }
}
