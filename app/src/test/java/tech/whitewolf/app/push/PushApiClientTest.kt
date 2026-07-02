package tech.whitewolf.app.push

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushApiClientTest {
    private lateinit var server: MockWebServer
    private fun client(token: String?) =
        PushApiClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), { token })

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun registerPostsEndpointWithBearer() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val ok = client("u.9999.sig").register("https://ntfy.whitewolf.tech/UPabc?up=1")
        assertTrue(ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/push/register", req.path)
        assertEquals("Bearer u.9999.sig", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("https://ntfy.whitewolf.tech/UPabc?up=1"))
    }

    @Test fun unregisterHitsUnregisterPath() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertTrue(client("t").unregister("https://ntfy.whitewolf.tech/UPabc?up=1"))
        assertEquals("/api/push/unregister", server.takeRequest().path)
    }

    @Test fun noTokenReturnsFalseAndSendsNothing() {
        val ok = client(null).register("https://ntfy.whitewolf.tech/UPabc?up=1")
        assertFalse(ok)
        assertEquals(0, server.requestCount)
    }

    @Test fun non2xxReturnsFalse() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse(client("t").register("https://ntfy.whitewolf.tech/UPabc?up=1"))
    }
}
