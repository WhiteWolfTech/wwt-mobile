package tech.whitewolf.app.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeCookies : WebCookies {
    var seededUrl: String? = null
    var seededHeader: String? = null
    var cleared = false
    override fun seed(url: String, setCookieHeader: String) { seededUrl = url; seededHeader = setCookieHeader }
    override fun clear(url: String) { cleared = true }
}

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private fun repo(store: TokenStore, cookies: WebCookies) =
        AuthRepository(OkHttpClient(), server.url("/").toString().trimEnd('/'), store, cookies)

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun freshStore() = TokenStore(object : SecureStore {
        val m = mutableMapOf<String, String>()
        override fun getString(key: String) = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }, nowSeconds = { 1000L })

    @Test fun successStoresTokenAndSeedsCookie() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "session=u.9999.sig; Path=/; HttpOnly")
                .setBody("""{"ok":true,"token":"u.9999.sig","expires":9999}""")
        )
        val store = freshStore()
        val cookies = FakeCookies()
        val result = repo(store, cookies).login("a@x.tech", "pw")

        assertEquals(LoginResult.Success, result)
        assertEquals("u.9999.sig", store.token())
        assertEquals(9999L, store.expiresAt())
        assertTrue(cookies.seededHeader!!.startsWith("session=u.9999.sig"))

        assertEquals(server.url("/").toString().trimEnd('/'), cookies.seededUrl)

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/login", sent.path)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"email\":\"a@x.tech\""))
        assertTrue(body.contains("\"password\":\"pw\""))
    }

    @Test fun unauthorizedReturnsInvalidCredentialsAndStoresNothing() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val store = freshStore()
        val cookies = FakeCookies()
        val result = repo(store, cookies).login("a@x.tech", "bad")
        assertEquals(LoginResult.InvalidCredentials, result)
        assertNull(store.token())
        assertNull(cookies.seededHeader)
    }

    @Test fun serverErrorReturnsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo(freshStore(), FakeCookies()).login("a@x.tech", "pw")
        assertTrue(result is LoginResult.Error)
    }

    @Test fun okFalseReturnsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":false,"token":"","expires":0}"""))
        val result = repo(freshStore(), FakeCookies()).login("a@x.tech", "pw")
        assertTrue(result is LoginResult.Error)
    }

    @Test fun blankTokenReturnsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"token":"  ","expires":9999}"""))
        val store = freshStore()
        val result = repo(store, FakeCookies()).login("a@x.tech", "pw")
        assertTrue(result is LoginResult.Error)
        assertNull(store.token())
    }

    @Test fun logoutClearsTokenAndCookies() {
        val store = freshStore(); store.save("t", 9999L)
        val cookies = FakeCookies()
        val r = repo(store, cookies)
        r.logout()
        assertNull(store.token())
        assertTrue(cookies.cleared)
    }
}
