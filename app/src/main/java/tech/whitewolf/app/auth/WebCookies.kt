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

/**
 * Builds the `Set-Cookie`-style line used to seed the WebView's session cookie from
 * the stored bearer token (the backend's session cookie value IS the token). Path=/
 * so it is sent for every request to the origin; Secure because the sub-apps are
 * HTTPS; HttpOnly so JavaScript in the WebView cannot read the token via
 * document.cookie (CookieManager still sends it on requests) — matching the
 * server's own session cookie; SameSite=Lax so it rides the top-level navigation
 * that loads the SPA.
 */
fun sessionCookieLine(token: String): String =
    "session=$token; Path=/; Secure; HttpOnly; SameSite=Lax"
