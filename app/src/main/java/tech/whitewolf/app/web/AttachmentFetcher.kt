package tech.whitewolf.app.web

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches one attachment into app-private storage as the signed-in user (WWT-238).
 * The app does this itself rather than through the system DownloadManager, which
 * can only write to shared storage and would keep the session cookie in its own
 * database for as long as the download entry exists.
 *
 * Plain [HttpURLConnection], so it runs on the JVM in tests. Blocking: call it off
 * the main thread.
 */
object AttachmentFetcher {
    /** Well above the mail server's 25 MB attachment limit; a guard, not a policy. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * Downloads [url] to [dest], or throws [IOException] and leaves nothing behind.
     * Redirects are refused, not followed: the request carries the session cookie,
     * and a redirect could send it anywhere. (The mail server's attachment routes
     * never redirect.) The body streams to a `.part` file that is renamed into
     * place only once complete, so a viewer never sees half a file.
     */
    fun fetch(url: String, cookie: String?, userAgent: String, dest: File, maxBytes: Long = MAX_BYTES) {
        val conn = URL(url).openConnection() as HttpURLConnection
        val part = File(dest.path + ".part")
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("attachment larger than $maxBytes bytes")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!part.renameTo(dest)) throw IOException("could not move download into place")
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
