package tech.whitewolf.app

import android.content.Context
import okhttp3.OkHttpClient
import tech.whitewolf.app.auth.AndroidWebCookies
import tech.whitewolf.app.auth.AuthRepository
import tech.whitewolf.app.auth.EncryptedPrefsStore
import tech.whitewolf.app.auth.TokenStore
import tech.whitewolf.app.subapp.SubAppRegistry

/** Manual DI: builds the real dependency graph for the shell. */
class AppContainer(context: Context) {
    private val http = OkHttpClient()
    private val tokenStore = TokenStore(EncryptedPrefsStore(context.applicationContext))
    private val cookies = AndroidWebCookies()

    // Auth base URL is the mail sub-app's origin (scheme://host) for now.
    private val baseUrl: String = SubAppRegistry.default().let {
        val u = java.net.URI(it.url); "${u.scheme}://${u.host}"
    }

    val auth = AuthRepository(http, baseUrl, tokenStore, cookies)
    val pushApiClient = tech.whitewolf.app.push.PushApiClient(http, baseUrl) { tokenStore.token() }
}
