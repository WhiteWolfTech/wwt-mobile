package tech.whitewolf.app

import android.content.Context
import okhttp3.OkHttpClient
import tech.whitewolf.app.auth.AndroidWebCookies
import tech.whitewolf.app.auth.AuthRepository
import tech.whitewolf.app.auth.EncryptedPrefsStore
import tech.whitewolf.app.auth.SessionBus
import tech.whitewolf.app.auth.TokenStore
import tech.whitewolf.app.subapp.SubAppRegistry

/** Manual DI: builds the real dependency graph for the shell. */
class AppContainer(context: Context) {
    private val http = OkHttpClient()
    private val secureStore = EncryptedPrefsStore(context.applicationContext)
    private val tokenStore = TokenStore(secureStore)
    private val cookies = AndroidWebCookies()

    // Auth base URL is the mail sub-app's origin (scheme://host) for now.
    private val baseUrl: String = SubAppRegistry.default().let {
        val u = java.net.URI(it.url); "${u.scheme}://${u.host}"
    }

    val sessionBus = SessionBus(tokenStore.token() != null)
    val auth = AuthRepository(http, baseUrl, tokenStore, cookies, sessionBus)

    // A 401 from the push registry means the bearer is dead server-side: drop the session
    // rather than retrying a stale token forever.
    val pushApiClient = tech.whitewolf.app.push.PushApiClient(
        http,
        baseUrl,
        { tokenStore.token() },
        { auth.invalidate() },
    )
    val pushEndpointStore = tech.whitewolf.app.push.PushEndpointStore(secureStore)
}
