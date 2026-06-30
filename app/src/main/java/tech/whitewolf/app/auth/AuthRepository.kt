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
) : Authenticator {
    @Serializable private data class LoginReq(val email: String, val password: String)
    @Serializable private data class LoginResp(val ok: Boolean = false, val token: String = "", val expires: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun isLoggedIn(): Boolean = tokenStore.token() != null

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

    override fun logout() {
        tokenStore.clear()
        cookies.clear(baseUrl)
    }
}
