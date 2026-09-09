package tech.whitewolf.app.subapp.mail

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.whitewolf.app.push.DeepLink
import tech.whitewolf.app.push.Notifications
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.SubAppPush
import tech.whitewolf.app.subapp.WakePayload

/**
 * Mail's push behaviour. Owns its wire format, its channel and its notification id, so
 * PushReceiver stays generic and never learns any sub-app's payload shape.
 *
 * One collapsing notification: mail says "you have mail", not "you have these mails", so
 * a single stable id is right. A sub-app that wants several to stack (video) derives its
 * id from the item instead.
 */
class MailPush : SubAppPush {
    private val id = SubAppId("mail")
    override val channelId = id.value
    override val channelName = "Mail"
    override val channelDescription = "New mail notifications"

    private val notificationId = 1
    private val json = Json { ignoreUnknownKeys = true }

    override fun decode(body: ByteArray): WakePayload? = try {
        val type = json.parseToJsonElement(body.decodeToString())
            .jsonObject["type"]?.jsonPrimitive?.content
        if (type == "new_mail") WakePayload(id, null) else null
    } catch (e: Exception) {
        null
    }

    override fun notify(context: Context, payload: WakePayload) {
        Notifications.ensureChannel(context, channelId, channelName, channelDescription)
        Notifications.post(
            context = context,
            channelId = channelId,
            notificationId = notificationId,
            title = "New mail",
            text = "You have new mail in WWT",
            tapUri = tapUri(payload),
        )
    }

    override fun tapTarget(payload: WakePayload): String =
        DeepLink.buildString(WakePayload(id, null))
}
