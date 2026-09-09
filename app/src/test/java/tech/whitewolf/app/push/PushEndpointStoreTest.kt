package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.whitewolf.app.auth.SecureStore
import tech.whitewolf.app.subapp.SubAppId

class PushEndpointStoreTest {
    private class FakeStore : SecureStore {
        private val m = HashMap<String, String>()
        override fun getString(key: String): String? = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    private val mail = SubAppId("mail")
    private val video = SubAppId("video")

    @Test fun returnsNullBeforeAnySave() {
        assertNull(PushEndpointStore(FakeStore()).get(mail))
    }

    @Test fun savesAndReadsBackEndpoint() {
        val s = PushEndpointStore(FakeStore())
        s.save(mail, "https://ntfy.whitewolf.tech/UPabc123")
        assertEquals("https://ntfy.whitewolf.tech/UPabc123", s.get(mail))
    }

    @Test fun clearRemovesEndpoint() {
        val s = PushEndpointStore(FakeStore())
        s.save(mail, "https://ntfy.whitewolf.tech/UPabc123")
        s.clear(mail)
        assertNull(s.get(mail))
    }

    /**
     * Pins the actual storage key. An implementation that ignored the sub-app id and
     * reused one shared key would still pass every round-trip test in this file, because
     * save() and get() would simply agree with each other on the same wrong key. Reading
     * the backing store directly, by the key this class is documented to use, is the only
     * way to catch that.
     */
    @Test fun savesUnderAPerSubAppKeyNotASharedOne() {
        val fake = FakeStore()
        PushEndpointStore(fake).save(mail, "https://ntfy.whitewolf.tech/UPabc123")
        assertEquals("https://ntfy.whitewolf.tech/UPabc123", fake.getString("push.endpoint.mail"))
    }

    @Test fun endpointsAreKeptPerSubApp() {
        val store = PushEndpointStore(FakeStore())
        store.save(mail, "https://ntfy.whitewolf.tech/m1")
        store.save(video, "https://ntfy.whitewolf.tech/v1")
        assertEquals("https://ntfy.whitewolf.tech/m1", store.get(mail))
        assertEquals("https://ntfy.whitewolf.tech/v1", store.get(video))
    }

    @Test fun clearingOneSubAppLeavesTheOther() {
        val store = PushEndpointStore(FakeStore())
        store.save(mail, "https://ntfy.whitewolf.tech/m1")
        store.save(video, "https://ntfy.whitewolf.tech/v1")
        store.clear(mail)
        assertNull(store.get(mail))
        assertEquals("https://ntfy.whitewolf.tech/v1", store.get(video))
    }

    @Test fun allReportsEverySavedEndpointForSignOut() {
        val store = PushEndpointStore(FakeStore())
        store.save(mail, "https://ntfy.whitewolf.tech/m1")
        assertEquals(
            mapOf(mail to "https://ntfy.whitewolf.tech/m1"),
            store.all(listOf(mail, video)),
        )
    }

    @Test fun allOmitsSubAppsWithNothingSaved() {
        val store = PushEndpointStore(FakeStore())
        assertEquals(emptyMap<SubAppId, String>(), store.all(listOf(mail, video)))
    }

    /**
     * Task 20 reads the pre-restructure single-key registration to retire it against the
     * backend. Pin the literal old key name directly on the backing store rather than
     * round-tripping through this class alone: nothing in this class writes that key any
     * more, so the only honest check is that legacyGet() still reads what the OLD shape
     * used to write there.
     */
    @Test fun legacyGetReadsTheOldSingleKey() {
        val fake = FakeStore()
        fake.putString("push.endpoint", "https://ntfy.whitewolf.tech/legacy1")
        assertEquals("https://ntfy.whitewolf.tech/legacy1", PushEndpointStore(fake).legacyGet())
    }

    @Test fun legacyGetIsUnaffectedByPerSubAppSaves() {
        val fake = FakeStore()
        fake.putString("push.endpoint", "https://ntfy.whitewolf.tech/legacy1")
        val store = PushEndpointStore(fake)
        store.save(mail, "https://ntfy.whitewolf.tech/m1")
        assertEquals("https://ntfy.whitewolf.tech/legacy1", store.legacyGet())
    }
}
