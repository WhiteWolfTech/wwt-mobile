package tech.whitewolf.app.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Authenticated calls to the backend push registry. The endpoint is the
 * UnifiedPush endpoint URL the distributor issued. Never throws; returns false
 * when there is no token or the request fails.
 */
class PushApiClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val token: () -> String?,
) {
    @Serializable private data class EndpointBody(val endpoint: String)
    private val json = Json
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun register(endpoint: String): Boolean = post("/api/push/register", endpoint)
    fun unregister(endpoint: String): Boolean = post("/api/push/unregister", endpoint)

    private fun post(path: String, endpoint: String): Boolean {
        val t = token() ?: return false
        val body = json.encodeToString(EndpointBody.serializer(), EndpointBody(endpoint))
            .toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $t")
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }
}
