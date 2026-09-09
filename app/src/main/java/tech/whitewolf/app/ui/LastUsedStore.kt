package tech.whitewolf.app.ui

import android.content.Context
import tech.whitewolf.app.subapp.SubAppId

/** Minimal key/value so the store is testable without Android. */
interface Prefs {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** Prefs over plain SharedPreferences — the last-used id is not a secret. */
class AndroidPrefs(context: Context) : Prefs {
    private val sp = context.applicationContext
        .getSharedPreferences("wwt.shell", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
}

/** Which sub-app to open on cold start. Re-validated on read, never trusted raw. */
class LastUsedStore(private val prefs: Prefs) {
    private val key = "shell.lastUsed"
    fun get(): SubAppId? = prefs.get(key)?.let { SubAppId.parse(it) }
    fun set(id: SubAppId) = prefs.put(key, id.value)
}
