package tech.whitewolf.app.push

import android.net.Uri
import tech.whitewolf.app.mailto.MailtoIntent
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
 *
 * WWT-253 adds one optional query param, `?compose=<mailto: link>`, written only when
 * [WakePayload.compose] is set — so every compose-less payload (every notification)
 * still builds exactly the string it always did, and PendingIntent equality is
 * untouched. The query is split off BEFORE the slash/itemId rules run, because the
 * encoded mailto: never contains a raw `/` but the old parser would otherwise see
 * `mail?compose=…` as the sub-app id. Other params are ignored, not rejected.
 *
 * MainActivity is exported, so any app can hand it a `wwt://` URI directly, skipping
 * MailtoActivity: the compose value is re-checked here (`mailto:` only, at most
 * [MailtoIntent.MAX_LEN]) and a bad one is dropped while the rest of the link stands.
 */
object DeepLink {
    const val SCHEME = "wwt"
    private const val AUTHORITY = "subapp"
    private const val PREFIX = "$SCHEME://$AUTHORITY/"
    private const val COMPOSE = "compose"

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun buildString(payload: WakePayload): String {
        val item = payload.itemId
        val tail = if (item.isNullOrEmpty()) "" else "/" + encode(item)
        val compose = payload.compose
        val query = if (compose.isNullOrEmpty()) "" else "?$COMPOSE=" + encode(compose)
        return PREFIX + payload.subAppId.value + tail + query
    }

    fun build(payload: WakePayload): Uri = Uri.parse(buildString(payload))

    fun parseString(raw: String?): WakePayload? {
        val s = raw ?: return null
        if (!s.startsWith(PREFIX)) return null
        val rest = s.removePrefix(PREFIX).substringBefore('?')
        val compose = composeFrom(s.substringAfter('?', ""))
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        val idPart = if (slash < 0) rest else rest.substring(0, slash)
        val id = SubAppId.parse(idPart) ?: return null
        if (slash < 0) {
            return WakePayload(id, compose = compose)
        }
        // After the slash, remainder must be non-empty and contain no further slashes
        val itemPart = rest.substring(slash + 1)
        if (itemPart.isEmpty() || itemPart.indexOf('/') >= 0) return null
        // MainActivity is exported, so a malformed escape (`%zz`) from another app must be an
        // invalid link, not an IllegalArgumentException out of onCreate (WWT-254).
        val item = runCatching { URLDecoder.decode(itemPart, "UTF-8") }.getOrNull() ?: return null
        return WakePayload(id, item, compose)
    }

    fun parse(uri: Uri?): WakePayload? = parseString(uri?.toString())

    /** The first `compose` param, decoded — or null if absent, malformed, oversize or not a mailto:. */
    private fun composeFrom(query: String): String? {
        val raw = query.split('&').firstOrNull { it.substringBefore('=') == COMPOSE }
            ?.substringAfter('=', "") ?: return null
        val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        return value.takeIf { it.length <= MailtoIntent.MAX_LEN && it.startsWith("mailto:", ignoreCase = true) }
    }
}
