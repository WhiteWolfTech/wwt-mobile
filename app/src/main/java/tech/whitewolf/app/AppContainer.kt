package tech.whitewolf.app

import android.content.Context
import okhttp3.OkHttpClient
import tech.whitewolf.app.auth.AndroidWebCookies
import tech.whitewolf.app.auth.AuthRepository
import tech.whitewolf.app.auth.EncryptedPrefsStore
import tech.whitewolf.app.auth.OidcAuthService
import tech.whitewolf.app.auth.OidcSsoLogin
import tech.whitewolf.app.auth.SessionBus
import tech.whitewolf.app.auth.SsoLogin
import tech.whitewolf.app.auth.TokenStore
import tech.whitewolf.app.net.ConnectivityMonitor
import tech.whitewolf.app.push.PushApiClient
import tech.whitewolf.app.push.PushEndpointStore
import tech.whitewolf.app.subapp.SubAppEntry
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.SubAppRegistry
import tech.whitewolf.app.subapp.SubAppScopes
import tech.whitewolf.app.subapp.mail.MailPush
import tech.whitewolf.app.subapp.mail.MailSubApp
import tech.whitewolf.app.subapp.mailTarget
import tech.whitewolf.app.ui.AndroidPrefs
import tech.whitewolf.app.ui.LastUsedStore

/** Manual DI: builds the real dependency graph for the shell. */
class AppContainer(context: Context) {
    private val http = OkHttpClient()
    private val secureStore = EncryptedPrefsStore(context.applicationContext)
    private val tokenStore = TokenStore(secureStore)
    private val cookies = AndroidWebCookies()

    // Read once: also seeds MailSubApp's url below, so the two can never disagree.
    private val mail = mailTarget()

    // Auth base URL is the mail sub-app's origin (scheme://host) for now.
    private val baseUrl: String = java.net.URI(mail.url).let { "${it.scheme}://${it.host}" }

    val sessionBus = SessionBus(tokenStore.token() != null)
    val auth = AuthRepository(http, baseUrl, tokenStore, cookies, sessionBus)

    // Native SSO: AppAuth runs the OIDC flow against wwt-auth; OidcSsoLogin threads the
    // resulting ID token into the mail backend's /api/auth/native to mint the session.
    val ssoLogin: SsoLogin =
        OidcSsoLogin(OidcAuthService(context.applicationContext), auth)

    val pushEndpointStore = PushEndpointStore(secureStore)

    // Reachability for a sub-app's offline/online error copy. A container-level (shell-
    // level) singleton, not per-composition the way ShellScreen used to own one: a sub-app
    // is CONSTRUCTED with what it needs, and MailSubApp needs this before any Compose tree
    // exists — and the next hosted-web sub-app will need this exact same instance too, so
    // it belongs at the shell, not duplicated per sub-app.
    //
    // Deliberately process-scoped and NEVER STOPPED — this is not a leak to fix later.
    // AppContainer itself is a process-scoped singleton (WwtApp.container is `by lazy`,
    // constructed once and held for the process's life), so there is no narrower owner to
    // call stop() from; a single network callback registration living as long as the
    // process it monitors is the correct shape here, matching sessionBus/pushStatusBus
    // elsewhere in this class, neither of which has a shutdown path either.
    val connectivity = ConnectivityMonitor(context.applicationContext).also { it.start() }

    // Per-sub-app retained state (WebViews, session objects today). Handed to every
    // SubApp that needs to survive a launcher round-trip. Discarding on sign-out is a
    // security requirement (stale DOM/localStorage must not outlive the session that
    // loaded it) — wired in ShellScreen's signOut lambda (the deliberate path) and its
    // loggedIn-observing LaunchedEffect (the involuntary 401-invalidation path); this
    // container only owns the scopes themselves.
    val scopes = SubAppScopes()

    val lastUsedStore = LastUsedStore(AndroidPrefs(context.applicationContext))

    // The ordered suite.
    val registry = SubAppRegistry(
        listOf(
            SubAppEntry(
                ui = MailSubApp(
                    url = mail.url,
                    scopes = scopes,
                    token = { auth.currentToken() },
                    online = { connectivity.online },
                ),
                push = MailPush(),
            ),
        ),
    )

    // One PushApiClient per sub-app instance — the UnifiedPush side already registers one
    // instance per sub-app (PushManager), and the backend registration call must match it
    // 1:1 so an endpoint is only ever registered/unregistered against its own sub-app's
    // registry. Every sub-app shares the shell's single baseUrl/token for now because
    // there is only one backend (mail's); Task 18 gives each sub-app its own base URL and
    // token supplier once Phase 4 wires up per-sub-app identity.
    private val pushClients: Map<SubAppId, PushApiClient> = registry.ids().associateWith {
        PushApiClient(http, baseUrl, { tokenStore.token() }, { auth.invalidate() })
    }

    fun pushClientFor(id: SubAppId): PushApiClient? = pushClients[id]
}
