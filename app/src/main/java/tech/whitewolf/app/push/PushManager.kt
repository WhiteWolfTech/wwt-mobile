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
}
