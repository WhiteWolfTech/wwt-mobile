package tech.whitewolf.app.push

import tech.whitewolf.app.auth.SecureStore

/**
 * Persists the last UnifiedPush endpoint URL the distributor issued, so sign-out
 * can unregister it with the backend (the connector's onUnregistered callback does
 * not carry the endpoint, and unregisterApp() mints a fresh token on next login).
 */
class PushEndpointStore(private val store: SecureStore) {
    private val key = "push.endpoint"
    fun save(endpoint: String) = store.putString(key, endpoint)
    fun get(): String? = store.getString(key)
    fun clear() = store.remove(key)
}
