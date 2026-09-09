package tech.whitewolf.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.whitewolf.app.MainActivity
import tech.whitewolf.app.R

/**
 * Generic channel/post helper. Knows nothing about any sub-app's payload shape, copy,
 * channel id or notification id — those are owned by each [SubAppPush] (e.g. MailPush).
 * Generic content only: no message body/subject ever passes through this object, only
 * whatever title/text its caller supplies.
 *
 * No-ops silently when the POST_NOTIFICATIONS permission is not granted (Android 13+).
 *
 * The tap target rides in the intent's DATA, not its extras — extras do not participate
 * in PendingIntent equality, so two notifications built with the same request code and
 * FLAG_UPDATE_CURRENT would share one intent and the second would silently overwrite the
 * first's payload. The request code is derived from the tap URI so distinct targets never
 * collide, and [post] can safely be called twice for the same notification id (a sub-app
 * posting generic copy, then replacing it once an enrichment fetch returns).
 */
object Notifications {
    fun ensureChannel(context: Context, channelId: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, name, NotificationManager.IMPORTANCE_HIGH,
            ).apply { this.description = description }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun post(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
        tapUri: Uri,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return  // no permission → skip silently

        val intent = Intent(context, MainActivity::class.java)
            .setData(tapUri)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            context, tapUri.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
