package tech.whitewolf.app.web

import java.io.ByteArrayOutputStream

/** What to hand Android's DownloadManager for one WebView download. */
data class DownloadPlan(val url: String, val fileName: String, val mimeType: String?)

/**
 * Decides whether a download the WebView was asked for may happen, and under what
 * name (WWT-238). The mail server serves attachments with `Content-Disposition:
 * attachment`, which a WebView drops unless something handles it; this is the
 * pure half of that handler, kept off Android APIs so it runs on the JVM.
 */
object DownloadPolicy {
    private const val FALLBACK_NAME = "attachment"
    private const val MAX_NAME = 120

    /**
     * A plan, or null to refuse. Only the sub-app's own origin over HTTPS is
     * allowed — the same rule [NavPolicy] applies to navigation — because the
     * download carries the user's session cookie: a page must not be able to point
     * it anywhere else.
     */
    fun plan(url: String, contentDisposition: String?, mimeType: String?, allowedHost: String): DownloadPlan? {
        if (!NavPolicy.isInApp(url, allowedHost)) return null
        return DownloadPlan(url, fileName(contentDisposition), mimeType?.takeIf { it.isNotBlank() })
    }

    /**
     * The file name from a Content-Disposition header, made safe to use as a path
     * under Downloads. RFC 2231/5987 `filename*=` (which the server uses for
     * non-ASCII names, via Go's mime.FormatMediaType) wins over plain `filename=`.
     * The name comes from whoever sent the mail, so path separators and control
     * characters are removed rather than trusted.
     */
    internal fun fileName(contentDisposition: String?): String {
        val params = contentDisposition?.let(::parameters).orEmpty()
        val raw = params["filename*"]?.let(::decodeExtValue) ?: params["filename"]
        return sanitize(raw)
    }

    private fun sanitize(raw: String?): String {
        if (raw == null) return FALLBACK_NAME
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filterNot { it.code < 0x20 || it.code == 0x7f }.trim()
        if (clean.isEmpty() || clean == "." || clean == "..") return FALLBACK_NAME
        return truncate(clean)
    }

    // Keep the extension when shortening, so the file still opens in the right app.
    private fun truncate(name: String): String {
        if (name.length <= MAX_NAME) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        return name.substring(0, MAX_NAME - ext.length) + ext
    }

    /** `type; a=b; c="d; e"` → {a: b, c: d; e}, names lowercased, quoted-pairs unescaped. */
    private fun parameters(header: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = header.indexOf(';').takeIf { it >= 0 } ?: return out
        while (i < header.length) {
            i++ // past ';'
            val eq = header.indexOf('=', i)
            if (eq < 0) break
            val name = header.substring(i, eq).trim().lowercase()
            var j = eq + 1
            while (j < header.length && header[j] == ' ') j++
            val value = StringBuilder()
            val quoted = j < header.length && header[j] == '"'
            if (quoted) {
                j++
                while (j < header.length && header[j] != '"') {
                    if (header[j] == '\\' && j + 1 < header.length) j++
                    value.append(header[j]); j++
                }
                j++ // past closing quote
                while (j < header.length && header[j] != ';') j++
            } else {
                while (j < header.length && header[j] != ';') { value.append(header[j]); j++ }
            }
            // A quoted value is taken exactly; a token is trimmed of surrounding space.
            if (name.isNotEmpty()) out.putIfAbsent(name, if (quoted) value.toString() else value.toString().trim())
            i = j
        }
        return out
    }

    /** RFC 5987 ext-value `charset'lang'pct-encoded`; only UTF-8 is accepted. */
    private fun decodeExtValue(v: String): String? {
        val parts = v.split('\'', limit = 3)
        if (parts.size != 3 || !parts[0].equals("utf-8", ignoreCase = true)) return null
        val bytes = ByteArrayOutputStream()
        val s = parts[2]
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '%' && k + 2 < s.length) {
                val hex = s.substring(k + 1, k + 3).toIntOrNull(16) ?: return null
                bytes.write(hex); k += 3
            } else {
                bytes.write(c.code and 0xff); k++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
