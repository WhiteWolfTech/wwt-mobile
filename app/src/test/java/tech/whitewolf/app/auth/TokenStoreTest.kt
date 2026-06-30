package tech.whitewolf.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeSecureStore : SecureStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class TokenStoreTest {
    @Test fun savesAndReturnsTokenWhenNotExpired() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 1000L })
        ts.save("u.exp.sig", expiresUnix = 2000L)
        assertEquals("u.exp.sig", ts.token())
        assertEquals(2000L, ts.expiresAt())
    }

    @Test fun returnsNullWhenExpired() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 5000L })
        ts.save("t", expiresUnix = 4999L)
        assertNull(ts.token())
    }

    @Test fun returnsNullWhenAbsent() {
        val ts = TokenStore(FakeSecureStore(), nowSeconds = { 0L })
        assertNull(ts.token())
        assertNull(ts.expiresAt())
    }

    @Test fun clearRemovesToken() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 0L })
        ts.save("t", expiresUnix = 10L)
        ts.clear()
        assertNull(ts.token())
        assertNull(ts.expiresAt())
    }

    @Test fun returnsNullWhenNowEqualsExpiry() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 3000L })
        ts.save("t", expiresUnix = 3000L)
        assertNull(ts.token())
    }
}
