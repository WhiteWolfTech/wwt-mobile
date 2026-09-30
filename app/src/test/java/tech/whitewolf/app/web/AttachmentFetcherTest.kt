package tech.whitewolf.app.web

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    // A minimal real HTTP/1.0 server on a loopback socket: the JDK's
    // com.sun.net.httpserver is not on the Android unit-test classpath.
    private lateinit var server: ServerSocket
    @Volatile private var seenCookie: String? = null
    @Volatile private var seenAgent: String? = null
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun start() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                s.use(::serve)
            }
        }
    }

    private fun serve(s: Socket) {
        val lines = generateSequence { s.getInputStream().readLine() }.takeWhile { it.isNotEmpty() }.toList()
        val path = lines.first().split(" ")[1]
        fun header(name: String) = lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val out = s.getOutputStream()
        when (path) {
            "/ok" -> {
                seenCookie = header("Cookie"); seenAgent = header("User-Agent")
                val body = "%PDF-fake".toByteArray()
                out.write("HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()); out.write(body)
            }
            "/redirect" -> out.write("HTTP/1.0 302 Found\r\nLocation: $base/ok\r\nContent-Length: 0\r\n\r\n".toByteArray())
            "/big" -> { out.write("HTTP/1.0 200 OK\r\n\r\n".toByteArray()); repeat(3) { out.write(ByteArray(1024)) } }
            else -> out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        out.flush()
    }

    // Reads one CRLF-terminated line without buffering past it.
    private fun java.io.InputStream.readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    @After fun stop() = server.close()

    private fun dest() = File(tmp.root, "report.pdf")

    @Test fun downloadsWithTheSessionCookieAndUserAgent() {
        val f = dest()
        AttachmentFetcher.fetch("$base/ok", cookie = "session=tok", userAgent = "wwt-test", dest = f)
        assertEquals("%PDF-fake", f.readText())
        assertEquals("session=tok", seenCookie)
        assertEquals("wwt-test", seenAgent)
        assertFalse(File(f.path + ".part").exists())
    }

    @Test fun noCookieIsSentWhenThereIsNone() {
        AttachmentFetcher.fetch("$base/ok", cookie = null, userAgent = "wwt-test", dest = dest())
        assertEquals(null, seenCookie)
    }

    @Test fun anErrorStatusFailsAndLeavesNoFile() {
        assertFails { AttachmentFetcher.fetch("$base/missing", null, "ua", dest()) }
        assertFalse(dest().exists())
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    // The cookie must never follow a redirect to wherever it points.
    @Test fun aRedirectIsRefusedNotFollowed() {
        assertFails { AttachmentFetcher.fetch("$base/redirect", "session=tok", "ua", dest()) }
        assertEquals("the redirect target must not have been fetched", null, seenCookie)
        assertFalse(dest().exists())
    }

    @Test fun anOversizedBodyIsAbortedAndCleanedUp() {
        assertFails { AttachmentFetcher.fetch("$base/big", null, "ua", dest(), maxBytes = 2048) }
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("expected an IOException") } catch (e: IOException) { /* expected */ }
    }
}
