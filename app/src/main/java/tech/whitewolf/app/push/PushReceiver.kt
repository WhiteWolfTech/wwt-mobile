package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * Receives UnifiedPush events. The registration network call runs off the main
 * thread on a background Thread, kept alive past the broadcast return by
 * goAsync()/PendingResult.finish() so it can't be killed mid-flight. A new
 * endpoint is sent to the backend. Every wake-up bumps the mail sub-app's
 * WakeBus tick; it also posts a generic "New mail" notification unless the
 * mailbox is what's currently on screen.
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

    // Task 13 replaces this with the registry-driven version.
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val app = tech.whitewolf.app.WwtApp.from(context)
        val id = tech.whitewolf.app.subapp.SubAppId("mail")
        app.wakeBus.signal(id)
        // No VisibleRoute yet (Task 12): "app is foreground" is the best available proxy and
        // preserves today's behaviour exactly while there is only one sub-app.
        if (wakeAction(app.isForeground, targetIsVisible = app.isForeground) == WakeAction.Background) {
            Notifications.showNewMail(app)
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Endpoint already gone at the distributor; backend prunes on 404/410 too.
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        android.util.Log.w("PushReceiver", "UnifiedPush registration failed for $instance")
    }
}
