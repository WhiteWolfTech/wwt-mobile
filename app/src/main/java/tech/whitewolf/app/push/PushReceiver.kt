package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver
import tech.whitewolf.app.AppContainer

/**
 * Receives UnifiedPush events. Network calls run off the main thread via a
 * background Thread so the broadcast isn't blocked. A new endpoint is sent to the
 * backend; each wake-up posts a generic "New mail" notification.
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val ok = AppContainer(app).pushApiClient.register(endpoint)
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
