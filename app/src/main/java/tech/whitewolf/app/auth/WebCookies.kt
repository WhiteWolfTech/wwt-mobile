package tech.whitewolf.app.auth

/** Seeds/clears the WebView's cookie jar so the hosted SPA is authenticated. */
interface WebCookies {
    fun seed(url: String, setCookieHeader: String)
    fun clear(url: String)
}

/**
 * Returns the raw Set-Cookie header value whose cookie name is exactly `session`,
 * or null. Matches the name token before '=' to avoid substring false positives.
 */
fun sessionCookieFrom(setCookieHeaders: List<String>): String? =
    setCookieHeaders.firstOrNull { header ->
        header.substringBefore('=', missingDelimiterValue = "").trim() == "session"
    }
