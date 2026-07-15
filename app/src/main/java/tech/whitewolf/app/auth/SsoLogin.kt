package tech.whitewolf.app.auth

import android.content.Intent

/**
 * The SSO half of sign-in, as the ViewModel sees it: build the intent that opens the
 * IdP in a Custom Tab, then turn the redirect result into a [LoginResult]. Split from
 * the concrete AppAuth + backend wiring so the ViewModel (and its unit tests) carry no
 * AppAuth types.
 */
interface SsoLogin {
    suspend fun authorizationIntent(): Intent
    suspend fun signIn(resultData: Intent): LoginResult
}

/**
 * Wires AppAuth ([OidcAuthService], the OIDC dance) to the backend exchange
 * ([AuthRepository.loginWithSso], which mints the app session from the ID token).
 */
class OidcSsoLogin(
    private val oidc: OidcAuthService,
    private val auth: AuthRepository,
) : SsoLogin {
    override suspend fun authorizationIntent(): Intent = oidc.authorizationIntent()

    override suspend fun signIn(resultData: Intent): LoginResult =
        auth.loginWithSso(oidc.completeAuthorization(resultData))
}
