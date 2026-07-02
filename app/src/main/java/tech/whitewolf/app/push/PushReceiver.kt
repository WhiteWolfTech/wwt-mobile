package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver
import tech.whitewolf.app.AppContainer

/**
 * Receives UnifiedPush events. The registration network call runs off the main
 * thread on a background Thread, kept alive past the broadcast return by
 * goAsync()/PendingResult.finish() so it can't be killed mid-flight. A new
 * endpoint is sent to the backend; each wake-up posts a generic "New mail"
 * notification.
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val container = AppContainer(app)
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
        Notifications.showNewMail(context.applicationContext)
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Endpoint already gone at the distributor; backend prunes on 404/410 too.
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        android.util.Log.w("PushReceiver", "UnifiedPush registration failed for $instance")
    }
}
