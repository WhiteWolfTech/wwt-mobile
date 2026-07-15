package tech.whitewolf.app.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult
    data class Error(val message: String) : LoginResult
}

interface Authenticator {
    fun login(email: String, password: String): LoginResult
    fun isLoggedIn(): Boolean
    fun logout()
}

class AuthRepository(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    private val cookies: WebCookies,
    private val session: SessionBus = SessionBus(tokenStore.token() != null),
) : Authenticator {
    @Serializable private data class LoginReq(val email: String, val password: String)
    @Serializable private data class NativeReq(@SerialName("id_token") val idToken: String)
    @Serializable private data class LoginResp(val ok: Boolean = false, val token: String = "", val expires: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Local check only — the token may still have been rejected by the server since it
     *  was stored (see [validate]). */
    override fun isLoggedIn(): Boolean = tokenStore.token() != null

    /** The current (non-expired) bearer token, or null. Used to seed the WebView
     *  session cookie at load time, since the cookie value IS this token. */
    fun currentToken(): String? = tokenStore.token()

    /**
     * Asks the server whether the stored bearer is still accepted, and drops it if not.
     * A locally-unexpired token can still be dead — the backend bumps token_version to
     * revoke every outstanding session (e.g. on the WWT-50 deploy), and nothing tells the
     * shell. Without this, `isLoggedIn()` keeps saying yes, the WebView falls back to the
     * SPA's own login form while the native TokenStore keeps a corpse, and push
     * registration fails forever on a stale bearer.
     *
     * Only a 401 signs you out. Offline, DNS failure, 5xx — anything that is not the
     * server explicitly rejecting the token — leaves the session intact: being unable to
     * reach the server is not the same as being signed out.
     *
     * Blocking; call off the main thread. Returns true when the session still stands.
     */
    fun validate(): Boolean {
        val t = tokenStore.token() ?: return false
        val req = Request.Builder()
            .url("$baseUrl/api/me")
            .header("Authorization", "Bearer $t")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                resp.body?.string() // drain so the connection can be reused
                if (resp.code == 401) {
                    invalidate()
                    false
                } else {
                    true
                }
            }
        } catch (e: IOException) {
            true // unreachable ≠ signed out
        }
    }

    /**
     * The server rejected our token: forget the session and let the login screen say so.
     * Safe to call from a background thread (the push client calls it on a 401).
     *
     * Distinct from [logout] only in what the user is told — a sign-out they asked for
     * needs no explanation, a session yanked out from under them does.
     */
    fun invalidate() {
        forget()
        session.invalidate()
    }

    private fun forget() {
        tokenStore.clear()
        cookies.clear(baseUrl)
    }

    override fun login(email: String, password: String): LoginResult {
        val body = json.encodeToString(LoginReq.serializer(), LoginReq(email, password))
            .toRequestBody(jsonMedia)
        val req = Request.Builder().url("$baseUrl/api/login").post(body).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty() // drain so the connection can be reused
                when {
                    resp.code == 401 -> LoginResult.InvalidCredentials
                    !resp.isSuccessful -> LoginResult.Error("server returned ${resp.code}")
                    else -> storeSession(resp, bodyStr)
                }
            }
        } catch (e: IOException) {
            LoginResult.Error(e.message ?: "network error")
        }
    }

    /**
     * Signs in with wwt-auth SSO. The caller (via AppAuth) has already run the
     * Authorization Code + PKCE flow against the identity provider and holds the
     * resulting [idToken]; here we hand it to the mail backend's native SSO endpoint,
     * which verifies it and returns the same session token + cookie that password login
     * would. Reuses the identical token-storing tail. Blocking; call off the main thread.
     */
    fun loginWithSso(idToken: String): LoginResult {
        val body = json.encodeToString(NativeReq.serializer(), NativeReq(idToken))
            .toRequestBody(jsonMedia)
        val req = Request.Builder().url("$baseUrl/api/auth/native").post(body).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) LoginResult.Error("SSO sign-in failed (${resp.code})")
                else storeSession(resp, bodyStr)
            }
        } catch (e: IOException) {
            LoginResult.Error(e.message ?: "network error")
        }
    }

    /**
     * Parses a successful login / SSO token response, stores the bearer, seeds the
     * WebView session cookie, and marks the session signed-in. Shared by the password
     * and SSO paths — both return the same `{ok, token, expires}` shape.
     */
    private fun storeSession(resp: Response, bodyStr: String): LoginResult =
        try {
            val parsed = json.decodeFromString(LoginResp.serializer(), bodyStr)
            if (!parsed.ok || parsed.token.isBlank()) {
                LoginResult.Error("malformed login response")
            } else {
                tokenStore.save(parsed.token, parsed.expires)
                sessionCookieFrom(resp.headers("Set-Cookie"))?.let { cookies.seed(baseUrl, it) }
                session.signedIn()
                LoginResult.Success
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            LoginResult.Error("malformed login response")
        }

    /** The user asked to sign out. Same teardown as [invalidate], but no notice: they know
     *  why they are looking at the login screen. */
    override fun logout() {
        forget()
        session.signedOut()
    }
}
