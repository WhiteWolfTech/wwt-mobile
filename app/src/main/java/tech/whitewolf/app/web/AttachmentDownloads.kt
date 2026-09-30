package tech.whitewolf.app.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * The Android half of attachment downloads (WWT-238): fetch in the background into
 * [AttachmentStore], then hand the file to a viewer. The pure rules live in
 * [DownloadPolicy], [AttachmentStore] and [AttachmentFetcher].
 */
object AttachmentDownloads {
    // One at a time: attachments are small, and a queue keeps two taps on the
    // same link from racing for the same file name.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun authority(ctx: Context) = "${ctx.packageName}.attachments"

    /** Download [plan] as the signed-in user and open it; reports progress and failure by toast. */
    fun start(ctx: Context, plan: DownloadPlan, userAgent: String) {
        val app = ctx.applicationContext
        Toast.makeText(app, "Downloading ${plan.fileName}…", Toast.LENGTH_SHORT).show()
        // Read on the calling (main) thread: CookieManager is not documented thread-safe.
        val cookie = CookieManager.getInstance().getCookie(plan.url)
        worker.execute {
            val result = runCatching {
                val file = AttachmentStore.of(app).newFile(plan.fileName)
                AttachmentFetcher.fetch(plan.url, cookie, userAgent, file)
                file
            }
            main.post {
                result.fold(
                    onSuccess = { open(ctx, it, plan.mimeType) },
                    onFailure = {
                        Log.w("AttachmentDownloads", "Download failed: ${plan.url}", it)
                        Toast.makeText(app, "Couldn't download ${plan.fileName}", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    /**
     * Opens [file] in a viewer the user picks. The viewer gets a content:// URI with a
     * one-off read grant for this file only; nothing is copied out of private storage.
     * A missing MIME type is taken from the extension so the right apps are offered.
     */
    fun open(ctx: Context, file: File, mimeType: String?) {
        val type = mimeType
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx.applicationContext, "No app can open ${file.name}", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * What sign-out removes beyond the session itself (WWT-238): downloaded attachments,
 * and Chromium's HTTP disk cache, which holds rendered mail bodies and images and
 * outlives the WebView — and app restarts — otherwise. Must run on the main thread
 * (it briefly creates a WebView: clearCache needs an instance, and the cache is shared
 * by every WebView in the process, so this works whether or not mail was opened).
 */
fun purgeSignedOutData(ctx: Context) {
    val app = ctx.applicationContext
    Thread { AttachmentStore.of(app).clear() }.start()
    WebView(ctx).apply { clearCache(true) }.destroy()
}
