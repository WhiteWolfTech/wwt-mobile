package tech.whitewolf.app.ui

import tech.whitewolf.app.subapp.SubAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MapPrefs(vararg pairs: Pair<String, String>) : Prefs {
    val m = mutableMapOf(*pairs)
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
}

class LastUsedStoreTest {
    @Test fun roundTrips() {
        val p = MapPrefs()
        LastUsedStore(p).set(SubAppId("video"))
        assertEquals(SubAppId("video"), LastUsedStore(p).get())
    }

    @Test fun absentOnFirstRun() {
        assertNull(LastUsedStore(MapPrefs()).get())
    }

    @Test fun aCorruptStoredValueReadsAsAbsent() {
        // Never let a bad pref become a channel id or instance name.
        assertNull(LastUsedStore(MapPrefs("shell.lastUsed" to "../etc")).get())
    }
}
