package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * Receives UnifiedPush events. The registration network call runs off the main
 * thread on a background Thread, kept alive past the broadcast return by
 * goAsync()/PendingResult.finish() so it can't be killed mid-flight. A new
 * endpoint is sent to the backend. Each wake-up either refreshes the mailbox
 * silently (app foregrounded → WakeBus tick) or posts a generic "New mail"
 * notification and a pending wake (app backgrounded).
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val container = tech.whitewolf.app.WwtApp.from(app).container
                container.pushEndpointStore.save(endpoint)
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
        val app = tech.whitewolf.app.WwtApp.from(context)
        when (wakeAction(app.isForeground)) {
            // Foreground: the user is looking at the app — refresh the mailbox
            // silently, no notification.
            WakeAction.Foreground -> app.wakeBus.signalWakeForeground()
            // Background: notify, and remember to refresh when the app returns.
            WakeAction.Background -> {
                Notifications.showNewMail(app)
                app.wakeBus.signalWakeBackground()
            }
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Endpoint already gone at the distributor; backend prunes on 404/410 too.
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        android.util.Log.w("PushReceiver", "UnifiedPush registration failed for $instance")
    }
}
