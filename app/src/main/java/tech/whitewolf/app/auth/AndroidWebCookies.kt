package tech.whitewolf.app.auth

import android.webkit.CookieManager

/** WebCookies over the global WebView CookieManager. */
class AndroidWebCookies : WebCookies {
    override fun seed(url: String, setCookieHeader: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(url, setCookieHeader)
        cm.flush()
    }

    override fun clear(url: String) {
        // Remove all cookies; the only cookies we set are the backend session.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
