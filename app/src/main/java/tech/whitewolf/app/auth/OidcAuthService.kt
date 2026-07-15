package tech.whitewolf.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import tech.whitewolf.app.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native OpenID Connect sign-in via AppAuth: an Authorization Code + PKCE (S256) flow
 * in a Chrome Custom Tab against wwt-auth (Authelia), using a PUBLIC client — no secret
 * ships in the app. It produces the raw ID token; the caller posts that to the mail
 * backend's /api/auth/native, which verifies it and mints the app's session (see
 * [AuthRepository.loginWithSso]).
 *
 * PKCE is generated automatically by AppAuth: the code verifier travels inside the
 * [AuthorizationRequest] and is replayed by `createTokenExchangeRequest()` at exchange
 * time, so it never leaves the device.
 */
class OidcAuthService(
    context: Context,
    private val issuer: String = BuildConfig.OIDC_ISSUER,
    private val clientId: String = BuildConfig.OIDC_CLIENT_ID,
    private val redirectUri: String = BuildConfig.OIDC_REDIRECT_URI,
) {
    private val service = AuthorizationService(context.applicationContext)

    /**
     * Builds the intent that opens the IdP sign-in page in a Custom Tab. Runs OIDC
     * discovery against the issuer, so call it off the main thread.
     */
    suspend fun authorizationIntent(): Intent {
        val config = discover()
        val request = AuthorizationRequest.Builder(
            config, clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri),
        ).setScope("openid profile email groups").build()
        return service.getAuthorizationRequestIntent(request)
    }

    /**
     * Completes the flow from the redirect result: parses the authorization response and
     * exchanges the code for tokens, returning the raw ID token. Throws on user cancel,
     * an authorization error, a failed exchange, or a missing ID token.
     */
    suspend fun completeAuthorization(data: Intent): String {
        val resp = AuthorizationResponse.fromIntent(data)
            ?: throw (AuthorizationException.fromIntent(data)
                ?: IllegalStateException("sign-in was cancelled"))
        val tokens = suspendCancellableCoroutine<TokenResponse> { cont ->
            service.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, ex ->
                if (tokenResp != null) cont.resume(tokenResp)
                else cont.resumeWithException(ex ?: IllegalStateException("token exchange failed"))
            }
        }
        return tokens.idToken ?: throw IllegalStateException("no id_token in token response")
    }

    private suspend fun discover(): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(issuer)) { config, ex ->
                if (config != null) cont.resume(config)
                else cont.resumeWithException(ex ?: IllegalStateException("OIDC discovery failed"))
            }
        }

    /** Releases the underlying Custom Tabs binding. */
    fun dispose() = service.dispose()
}
