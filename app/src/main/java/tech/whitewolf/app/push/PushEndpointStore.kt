package tech.whitewolf.app.push

import tech.whitewolf.app.auth.SecureStore
import tech.whitewolf.app.subapp.SubAppId

/**
 * Persists the last UnifiedPush endpoint URL each sub-app's distributor instance
 * issued, so sign-out can unregister every one of them with its own backend (the
 * connector's onUnregistered callback does not carry the endpoint, and
 * unregisterApp() mints a fresh token on next login). Keyed per sub-app id: one
 * UnifiedPush instance per sub-app means one independent endpoint per sub-app.
 */
class PushEndpointStore(private val store: SecureStore) {
    private fun key(id: SubAppId) = "push.endpoint.${id.value}"

    fun save(id: SubAppId, endpoint: String) = store.putString(key(id), endpoint)
    fun get(id: SubAppId): String? = store.getString(key(id))
    fun clear(id: SubAppId) = store.remove(key(id))

    /** Every saved endpoint among [ids] that actually has one, for sign-out to unregister.
     *  A sub-app with nothing saved is omitted rather than mapped to null. */
    fun all(ids: List<SubAppId>): Map<SubAppId, String> =
        ids.mapNotNull { id -> get(id)?.let { id to it } }.toMap()

    /**
     * Reads the OLD pre-restructure single-key registration ("push.endpoint", written
     * before endpoints were kept per sub-app). Nothing in this class writes that key any
     * more; kept solely so Task 20 can retire that dead registration against the backend
     * during sign-out — dropping this would strand it there forever.
     */
    fun legacyGet(): String? = store.getString(LEGACY_KEY)

    private companion object {
        const val LEGACY_KEY = "push.endpoint"
    }
}
