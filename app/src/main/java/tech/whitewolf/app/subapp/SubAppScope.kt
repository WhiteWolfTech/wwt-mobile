package tech.whitewolf.app.subapp

import android.util.Log

/** Something a sub-app keeps across a launcher round-trip and must release on discard. */
interface Retained {
    fun onDiscard()
}

/**
 * Per-sub-app retained state, so navigating to the launcher and back does not rebuild a
 * sub-app's content. Owned by the shell but keyed by sub-app, so the shell never learns
 * what is being retained: mail keeps a MailWebSession here, video will keep its list
 * state and MediaController binding.
 *
 * discardAll() runs on sign-out. That is a security requirement, not hygiene — a scope
 * that outlived sign-out would re-attach the previous user's live DOM and localStorage
 * under a different session cookie.
 */
class SubAppScopes {
    private val held = mutableMapOf<SubAppId, Retained>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Retained> getOrPut(id: SubAppId, create: () -> T): T =
        held.getOrPut(id, create) as T

    fun discard(id: SubAppId) {
        val item = held.remove(id)
        if (item != null) {
            runCatching { item.onDiscard() }.onFailure {
                Log.w("SubAppScopes", "Error discarding $id", it)
            }
        }
    }

    fun discardAll() {
        val all = held.values.toList()
        held.clear()
        all.forEach { item ->
            runCatching { item.onDiscard() }.onFailure {
                Log.w("SubAppScopes", "Error during discardAll", it)
            }
        }
    }
}
