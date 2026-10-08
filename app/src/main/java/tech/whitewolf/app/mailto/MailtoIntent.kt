package tech.whitewolf.app.mailto

import java.net.URLEncoder

/**
 * Turns an incoming mailto: intent (WWT-253) into ONE `mailto:` string — the only shape
 * that crosses into the SPA, which parses it itself (web/src/mailto.ts).
 *
 * Why fold the extras in at all: the canonical Android way to ask for an email is
 * ACTION_SENDTO with a bare `mailto:` as data and every field in EXTRA_EMAIL / EXTRA_CC /
 * EXTRA_SUBJECT / EXTRA_TEXT. Reading only the data would open an empty Compose for most
 * "Email us" buttons in native apps. A field the URI already carries wins over its extra:
 * the URI is what the user actually tapped, the extras are a caller's fallback.
 *
 * Bcc (in either form) is deliberately not folded in; the SPA drops a `bcc=` param too, so
 * a link can never silently copy someone in.
 *
 * Values are percent-encoded per RFC 3986 — never `+` for a space, which the SPA (rightly)
 * keeps literal — the same `URLEncoder` + `%20` trick [tech.whitewolf.app.push.DeepLink]
 * uses. Plain strings in, plain string out, so the whole thing is a JVM test; the activity
 * reads the Bundle.
 */
object MailtoIntent {
    /**
     * Upper bound on the whole mailto: string. It rides in an Intent and then as an
     * argument to evaluateJavascript, and a hostile caller controls its size; 32 KiB is
     * far beyond any real link. [tech.whitewolf.app.push.DeepLink] enforces it again on
     * the way in, because MainActivity is exported and can be called directly.
     */
    const val MAX_LEN = 32 * 1024

    private const val SCHEME = "mailto:"

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Builds one mailto: string from the intent's data plus the SENDTO extras. Returns null if not mailto. */
    fun toMailto(
        dataString: String?,
        emails: List<String>?,
        ccs: List<String>?,
        subject: String?,
        text: String?,
    ): String? {
        val data = dataString ?: return null
        if (!data.startsWith(SCHEME, ignoreCase = true)) return null
        // A fragment has no meaning in a mailto: and would swallow any param appended after it.
        val rest = data.substring(SCHEME.length).substringBefore('#')
        val path = rest.substringBefore('?')
        val params = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        val keys = params.map { it.substringBefore('=').lowercase() }.toSet()

        fun build(withBody: Boolean): String {
            val to = path.ifEmpty {
                if ("to" in keys) "" else emails.orEmpty().filter { it.isNotBlank() }.joinToString(",") { encode(it) }
            }
            val out = params.filter { withBody || it.substringBefore('=').lowercase() != "body" }.toMutableList()
            val cc = ccs.orEmpty().filter { it.isNotBlank() }
            if ("cc" !in keys && cc.isNotEmpty()) out += "cc=" + cc.joinToString(",") { encode(it) }
            if ("subject" !in keys && !subject.isNullOrEmpty()) out += "subject=" + encode(subject)
            if (withBody && "body" !in keys && !text.isNullOrEmpty()) out += "body=" + encode(text)
            return SCHEME + to + if (out.isEmpty()) "" else "?" + out.joinToString("&")
        }

        // Over the cap, the body is what goes first: an addressed, titled Compose with an
        // empty body is still useful; one that silently failed to open is not.
        return build(withBody = true).takeIf { it.length <= MAX_LEN }
            ?: build(withBody = false).takeIf { it.length <= MAX_LEN }
    }
}
