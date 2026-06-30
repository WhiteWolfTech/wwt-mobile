package tech.whitewolf.app.auth

/** Minimal key/value string storage, so TokenStore is testable without Android. */
interface SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
