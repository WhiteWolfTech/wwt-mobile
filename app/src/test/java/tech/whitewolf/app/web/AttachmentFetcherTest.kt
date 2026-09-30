package tech.whitewolf.app.web

import java.io.File
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// WWT-238: the app fetches an attachment itself into private storage, as the
// signed-in user, against a real local HTTP server.
class AttachmentFetcherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun dest() = File(tmp.root, "report.pdf")
    private fun url(path: String) = server.url(path).toString()

    @Test fun downloadsWithTheSessionCookieAndUserAgent() {
        server.enqueue(MockResponse().setBody("%PDF-fake"))
        val f = dest()
        AttachmentFetcher.fetch(url("/ok"), cookie = "session=tok", userAgent = "wwt-test", dest = f)
        assertEquals("%PDF-fake", f.readText())
        val req = server.takeRequest()
        assertEquals("session=tok", req.getHeader("Cookie"))
        assertEquals("wwt-test", req.getHeader("User-Agent"))
        assertFalse(File(f.path + ".part").exists())
    }

    @Test fun noCookieIsSentWhenThereIsNone() {
        server.enqueue(MockResponse().setBody("x"))
        AttachmentFetcher.fetch(url("/ok"), cookie = null, userAgent = "wwt-test", dest = dest())
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun anErrorStatusFailsAndLeavesNoFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFails { AttachmentFetcher.fetch(url("/missing"), null, "ua", dest()) }
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    // The cookie must never follow a redirect to wherever it points.
    @Test fun aRedirectIsRefusedNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url("/elsewhere")))
        server.enqueue(MockResponse().setBody("should never be fetched"))
        assertFails { AttachmentFetcher.fetch(url("/redirect"), "session=tok", "ua", dest()) }
        assertEquals("only the original request was made", 1, server.requestCount)
        assertFalse(dest().exists())
    }

    @Test fun anOversizedBodyIsAbortedAndCleanedUp() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(3 * 1024)), 1024))
        assertFails { AttachmentFetcher.fetch(url("/big"), null, "ua", dest(), maxBytes = 2048) }
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("expected an IOException") } catch (e: IOException) { /* expected */ }
    }
}
