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
 *
 * [id] is CONSTRUCTED in (from `MailSubApp.ID` — see `AppContainer`) rather than declared
 * here, so mail's id has exactly one definition. Before this, `MailSubApp` and `MailPush`
 * each declared their own `SubAppId("mail")` literal; a rename to one without the other
 * would have routing use one id while a tapped notification's target used the other,
 * surfacing only as "tapping the notification opens nothing" on a device.
 */
class MailPush(private val id: SubAppId) : SubAppPush {
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

    // Uses payload.subAppId — the id it is GIVEN — rather than the constructor's own
    // `id` field: this is the same routing decision `decode()` already made when it
    // built the WakePayload notify() was called with, so tapTarget must not silently
    // repeat that decision from a second, independently-drifting source of truth. Today
    // that is always this instance's own `id` (mail decodes only its own payloads), but
    // the two are no longer assumed equal here. Still drops any item id: mail's tap
    // target never carries one.
    override fun tapTarget(payload: WakePayload): String =
        DeepLink.buildString(WakePayload(payload.subAppId, null))
}
