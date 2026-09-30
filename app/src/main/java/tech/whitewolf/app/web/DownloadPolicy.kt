package tech.whitewolf.app.web

import java.io.ByteArrayOutputStream
import java.net.URI

/** One attachment download the WebView asked for, approved and named. */
data class DownloadPlan(val url: String, val fileName: String, val mimeType: String?)

/**
 * Decides whether a download the WebView was asked for may happen, and under what
 * name (WWT-238). The mail server serves attachments with `Content-Disposition:
 * attachment`, which a WebView drops unless something handles it; this is the
 * pure half of that handler, kept off Android APIs so it runs on the JVM.
 */
object DownloadPolicy {
    private const val FALLBACK_NAME = "attachment"
    // Bytes, not characters: filesystems cap a name at 255 bytes, and a
    // multibyte name hits that long before 255 characters. Headroom for the
    // "-N" suffix AttachmentStore adds on a collision.
    private const val MAX_NAME_BYTES = 200

    /**
     * A plan, or null to refuse. Only exactly the mail host over HTTPS: the fetch
     * carries the user's session cookie, so a page must not be able to point it
     * anywhere else. Stricter than [NavPolicy], which also admits subdomains —
     * the session cookie is host-only, so a subdomain would have no session.
     */
    fun plan(url: String, contentDisposition: String?, mimeType: String?, allowedHost: String): DownloadPlan? {
        if (!isExactlyHost(url, allowedHost)) return null
        return DownloadPlan(url, fileName(contentDisposition), mimeType?.takeIf { it.isNotBlank() })
    }

    private fun isExactlyHost(url: String, allowedHost: String): Boolean {
        val uri = try { URI(url) } catch (e: Exception) { return false }
        if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null) return false
        return uri.host?.lowercase()?.trimEnd('.') == allowedHost.lowercase()
    }

    /**
     * The file name from a Content-Disposition header, made safe to use as a file
     * name in the attachment store. RFC 2231/5987 `filename*=` (which the server uses for
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

    // Keep the extension when shortening, so the file still opens in the right
    // app, and cut only between code points, so no surrogate pair is split.
    private fun truncate(name: String): String {
        if (utf8Len(name) <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        val budget = MAX_NAME_BYTES - utf8Len(ext)
        val stem = StringBuilder()
        var used = 0
        var i = 0
        while (i < name.length - ext.length) {
            val cp = name.codePointAt(i)
            val len = utf8Len(String(Character.toChars(cp)))
            if (used + len > budget) break
            stem.appendCodePoint(cp); used += len
            i += Character.charCount(cp)
        }
        return stem.toString() + ext
    }

    private fun utf8Len(s: String) = s.toByteArray(Charsets.UTF_8).size

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
        // Not bytes.toString(Charset): that overload is API 33, and minSdk is 29.
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
