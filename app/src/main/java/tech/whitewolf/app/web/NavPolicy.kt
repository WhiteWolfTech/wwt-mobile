package tech.whitewolf.app.web

import java.net.URI

/** Decides whether a navigation target stays in the WebView or opens externally. */
object NavPolicy {
    fun isInApp(url: String, allowedHost: String): Boolean {
        val uri = try { URI(url) } catch (e: Exception) { return false }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val allowed = allowedHost.lowercase()
        return host == allowed || host.endsWith(".$allowed")
    }
}
