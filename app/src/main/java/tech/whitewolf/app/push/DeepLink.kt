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
 *
 * An empty itemId is treated as absent and is emitted and parsed as the target-only form.
 */
object DeepLink {
    const val SCHEME = "wwt"
    private const val AUTHORITY = "subapp"
    private const val PREFIX = "$SCHEME://$AUTHORITY/"

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun buildString(payload: WakePayload): String {
        val item = payload.itemId
        val tail = if (item.isNullOrEmpty()) "" else "/" + encode(item)
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
        if (slash < 0) {
            return WakePayload(id)
        }
        // After the slash, remainder must be non-empty and contain no further slashes
        val itemPart = rest.substring(slash + 1)
        if (itemPart.isEmpty() || itemPart.indexOf('/') >= 0) return null
        val item = URLDecoder.decode(itemPart, "UTF-8")
        return WakePayload(id, item)
    }

    fun parse(uri: Uri?): WakePayload? = parseString(uri?.toString())
}
