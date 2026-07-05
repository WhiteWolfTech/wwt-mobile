package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/** Thin lifecycle wrapper over the UnifiedPush connector. */
class PushManager(private val context: Context) {
    fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /** Register with the saved distributor (or the only one available). Safe to call repeatedly. */
    fun enable() {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return
        if (distributors.size == 1) UnifiedPush.saveDistributor(context, distributors.first())
        UnifiedPush.registerApp(context)
    }

    fun disable() {
        UnifiedPush.unregisterApp(context)
    }

    /**
     * Drop the current registration and register anew. Needed after the distributor's
     * server changes: ntfy pins a registration to the server that was its default when
     * the registration was created, so enable() alone returns the stale endpoint forever.
     */
    fun reregister() {
        UnifiedPush.unregisterApp(context)
        enable()
    }
}
