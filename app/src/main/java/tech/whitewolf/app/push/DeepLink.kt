package tech.whitewolf.app.push

import android.net.Uri
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Notification targets, as `wwt://subapp/<subAppId>[/<itemId>]`.
 *
 * The target rides in the intent's DATA rather than its extras. Extras do not
 * participate in PendingIntent equality, so two notifications built with the same
 * request code and FLAG_UPDATE_CURRENT would share one intent and the second would
 * silently overwrite the first's payload — every tap landing on the same item.
 *
 * String forms are the primitive so the whole thing is JVM-testable; android.net.Uri is
 * stubbed in unit tests.
 */
object DeepLink {
    const val SCHEME = "wwt"
    private const val AUTHORITY = "subapp"
    private const val PREFIX = "$SCHEME://$AUTHORITY/"

    fun buildString(payload: WakePayload): String {
        val item = payload.itemId
        val tail = if (item.isNullOrEmpty()) "" else "/" + URLEncoder.encode(item, "UTF-8")
        return PREFIX + payload.subAppId.value + tail
    }

    fun build(payload: WakePayload): Uri = Uri.parse(buildString(payload))

    fun parseString(raw: String?): WakePayload? {
        val s = raw ?: return null
        if (!s.startsWith(PREFIX)) return null
        val rest = s.removePrefix(PREFIX)
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        val idPart = if (slash < 0) rest else rest.substring(0, slash)
        val id = SubAppId.parse(idPart) ?: return null
        val item = if (slash < 0 || slash == rest.lastIndex) null
        else URLDecoder.decode(rest.substring(slash + 1), "UTF-8")
        return WakePayload(id, item)
    }

    fun parse(uri: Uri?): WakePayload? = parseString(uri?.toString())
}
