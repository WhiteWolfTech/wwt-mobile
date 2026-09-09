package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.subapp.SubAppId

/**
 * Receives UnifiedPush events. The registration network call runs off the main
 * thread on a background Thread, kept alive past the broadcast return by
 * goAsync()/PendingResult.finish() so it can't be killed mid-flight. A new
 * endpoint is sent to the backend.
 *
 * onMessage is registry-driven: it routes by `instance` alone and never learns any
 * sub-app's payload shape. Every wake-up bumps the target sub-app's WakeBus tick; a
 * notification is posted only when that sub-app is not what's currently on screen.
 * Unknown instance, no push behaviour registered, or an undecodable payload → return
 * quietly. A newer build's notification must never crash an older shell.
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val wwtApp = tech.whitewolf.app.WwtApp.from(app)
                val container = wwtApp.container
                container.pushEndpointStore.save(endpoint)
                // Surface push health from the endpoint host now — independent of whether
                // the backend register below succeeds.
                wwtApp.pushStatusBus.set(
                    pushStatusForEndpoint(endpoint, tech.whitewolf.app.BuildConfig.NTFY_HOST)
                )
                val ok = container.pushApiClient.register(endpoint)
                if (!ok) android.util.Log.w("PushReceiver", "push endpoint registration failed")
            } catch (e: Throwable) {
                android.util.Log.w("PushReceiver", "push endpoint registration error", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val app = WwtApp.from(context)
        val id = SubAppId.parse(instance) ?: return
        val entry = app.container.registry.byId(id) ?: return   // unknown -> ignore, never crash
        val payload = entry.push?.decode(message) ?: return
        // Always bump the tick, unconditionally and before the notify branch below: both
        // wake arms (silent refresh and notify-then-refresh-on-open) must advance it, or a
        // user switching to the target from the launcher reads stale content — there is no
        // ON_RESUME to save them, because the Activity never stopped.
        app.wakeBus.signal(id)
        val visible = app.visibleRoute.isVisible(id)
        if (wakeAction(app.isForeground, visible) == WakeAction.Background) {
            entry.push.notify(app, payload)
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Endpoint already gone at the distributor; backend prunes on 404/410 too.
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        android.util.Log.w("PushReceiver", "UnifiedPush registration failed for $instance")
    }
}
