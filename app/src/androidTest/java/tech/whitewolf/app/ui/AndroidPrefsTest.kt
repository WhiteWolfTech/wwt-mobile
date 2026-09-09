package tech.whitewolf.app.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPrefsTest {
    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("wwt.shell", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun roundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AndroidPrefs(ctx)
        prefs.put("testKey", "testValue")
        assertEquals("testValue", prefs.get("testKey"))
    }

    @Test
    fun absentKeyReturnsNull() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AndroidPrefs(ctx)
        assertNull(prefs.get("missingKey"))
    }

    @Test
    fun multipleWrites() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AndroidPrefs(ctx)
        prefs.put("key1", "value1")
        prefs.put("key2", "value2")
        assertEquals("value1", prefs.get("key1"))
        assertEquals("value2", prefs.get("key2"))
    }
}
