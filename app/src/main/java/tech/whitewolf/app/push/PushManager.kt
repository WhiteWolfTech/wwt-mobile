package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import tech.whitewolf.app.subapp.SubAppId

/**
 * Thin lifecycle wrapper over the UnifiedPush connector, registering one instance per
 * sub-app. [ids] is a function rather than a fixed list because the registry it reads
 * from can grow between calls within the same process.
 */
class PushManager(private val context: Context, private val ids: () -> List<SubAppId>) {
    fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /** Register every sub-app's instance with the saved distributor (or the only one
     *  available). Safe to call repeatedly. */
    fun enable() {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return
        if (distributors.size == 1) UnifiedPush.saveDistributor(context, distributors.first())
        ids().forEach { UnifiedPush.registerApp(context, it.value) }
    }

    fun disable() {
        ids().forEach { UnifiedPush.unregisterApp(context, it.value) }
    }

    /**
     * Drop every sub-app's registration and register anew. ntfy pins a registration to
     * whichever server was its default when the registration was created, so enable()
     * alone returns the stale endpoint forever after the user fixes it.
     *
     * Order matters: unregister EVERY instance first, then enable(). The connector drops
     * its saved distributor once the LAST instance is unregistered, and enable() only
     * re-saves a distributor when getDistributors() returns exactly one. Interleaving
     * unregister/register per instance (or calling enable() before every instance is
     * unregistered) risks enable() running with no saved distributor and nothing to
     * register the next instance against — the user who fixed their server setting would
     * never recover push.
     */
    fun reregister() {
        disable()
        enable()
    }
}
