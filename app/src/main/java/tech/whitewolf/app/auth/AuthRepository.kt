package tech.whitewolf.app.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
     * Forgets the session: clears the native bearer and the WebView's cookie jar, and
     * flips [SessionBus] so the shell shows the native login. Safe to call from a
     * background thread (the push client calls it on a 401).
     */
    fun invalidate() {
        tokenStore.clear()
        cookies.clear(baseUrl)
        session.set(false)
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
                    else -> {
                        try {
                            val parsed = json.decodeFromString(LoginResp.serializer(), bodyStr)
                            if (!parsed.ok || parsed.token.isBlank()) {
                                LoginResult.Error("malformed login response")
                            } else {
                                tokenStore.save(parsed.token, parsed.expires)
                                sessionCookieFrom(resp.headers("Set-Cookie"))?.let { cookies.seed(baseUrl, it) }
                                session.set(true)
                                LoginResult.Success
                            }
                        } catch (e: kotlinx.serialization.SerializationException) {
                            LoginResult.Error("malformed login response")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            LoginResult.Error(e.message ?: "network error")
        }
    }

    override fun logout() = invalidate()
}
