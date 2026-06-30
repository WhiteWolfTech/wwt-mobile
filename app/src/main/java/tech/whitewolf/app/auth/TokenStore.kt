package tech.whitewolf.app.auth

/**
 * Persists the native bearer token and its expiry over a [SecureStore].
 * token() returns null once the stored expiry has passed (local check; the token
 * and the session cookie share the backend's 7-day lifetime).
 */
class TokenStore(
    private val store: SecureStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val keyToken = "auth.token"
    private val keyExpires = "auth.expires"

    fun save(token: String, expiresUnix: Long) {
        store.putString(keyToken, token)
        store.putString(keyExpires, expiresUnix.toString())
    }

    fun token(): String? {
        val t = store.getString(keyToken) ?: return null
        val exp = store.getString(keyExpires)?.toLongOrNull() ?: return null
        return if (nowSeconds() < exp) t else null
    }

    fun expiresAt(): Long? = store.getString(keyExpires)?.toLongOrNull()

    fun clear() {
        store.remove(keyToken)
        store.remove(keyExpires)
    }
}
