package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.whitewolf.app.auth.SecureStore

class PushEndpointStoreTest {
    private class FakeStore : SecureStore {
        private val m = HashMap<String, String>()
        override fun getString(key: String): String? = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    @Test fun returnsNullBeforeAnySave() {
        assertNull(PushEndpointStore(FakeStore()).get())
    }

    @Test fun savesAndReadsBackEndpoint() {
        val s = PushEndpointStore(FakeStore())
        s.save("https://ntfy.whitewolf.tech/UPabc123")
        assertEquals("https://ntfy.whitewolf.tech/UPabc123", s.get())
    }

    @Test fun clearRemovesEndpoint() {
        val s = PushEndpointStore(FakeStore())
        s.save("https://ntfy.whitewolf.tech/UPabc123")
        s.clear()
        assertNull(s.get())
    }
}
