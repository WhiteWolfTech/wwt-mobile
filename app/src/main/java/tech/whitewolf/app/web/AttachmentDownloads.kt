package tech.whitewolf.app.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import tech.whitewolf.app.WwtApp

/**
 * The Android half of attachment downloads (WWT-238): fetch in the background into
 * [AttachmentStore], then hand the file to a viewer. The pure rules live in
 * [DownloadPolicy], [AttachmentStore], [AttachmentFetcher], [viewerMimeType] and
 * [DownloadGeneration].
 */
object AttachmentDownloads {
    // One at a time: attachments are small, and a queue keeps two taps on the
    // same link from racing for the same file name.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Bumped by sign-out; a download started before it is the previous user's. */
    internal val generation = DownloadGeneration()

    fun authority(ctx: Context) = "${ctx.packageName}.attachments"

    /** Download [plan] as the signed-in user and open it; reports progress and failure by toast. */
    fun start(ctx: Context, plan: DownloadPlan, userAgent: String) {
        // The application context only: nothing below needs an Activity (the
        // chooser starts with FLAG_ACTIVITY_NEW_TASK), and a fetch can outlive the
        // screen that started it by tens of seconds.
        val app = ctx.applicationContext
        Toast.makeText(app, "Downloading ${plan.fileName}…", Toast.LENGTH_SHORT).show()
        // Read on the calling (main) thread: CookieManager is not documented thread-safe.
        val cookie = CookieManager.getInstance().getCookie(plan.url)
        val startedIn = generation.current()
        worker.execute {
            val result = runCatching {
                val file = AttachmentStore.of(app).newFile(plan.fileName)
                AttachmentFetcher.fetch(plan.url, cookie, userAgent, file)
                file
            }
            main.post {
                // Signed out while this was in flight: the file is the previous
                // user's, so it is neither kept nor opened over the login screen.
                if (!generation.isCurrent(startedIn)) {
                    result.getOrNull()?.delete()
                    return@post
                }
                result.fold(
                    onSuccess = { finish(app, it, plan.mimeType) },
                    onFailure = {
                        Log.w("AttachmentDownloads", "Download failed: ${plan.url}", it)
                        Toast.makeText(app, "Couldn't download ${plan.fileName}", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    // Android blocks activity starts from the background (API 29+) without telling
    // the caller, so a download that finishes after the user left the app says so
    // instead of opening a viewer that would silently never appear.
    private fun finish(app: Context, file: File, mimeType: String?) {
        if (WwtApp.from(app).isForeground) {
            open(app, file, mimeType)
        } else {
            Toast.makeText(app, "Downloaded ${file.name} — tap it again to open", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens [file] in a viewer the user picks. The viewer gets a content:// URI with a
     * one-off read grant for this file only; nothing is copied out of private storage.
     * (The chooser always resolves, and shows its own message when no app can open
     * the type, so there is no ActivityNotFoundException to handle here.)
     */
    fun open(ctx: Context, file: File, mimeType: String?) {
        val type = viewerMimeType(mimeType, file.name) { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The type a viewer is offered the file as. The server passes the sender's own
 * Content-Type through, and senders often say application/octet-stream for
 * everything; that, or nothing, defers to the file's extension via [byExtension].
 */
internal fun viewerMimeType(serverType: String?, fileName: String, byExtension: (String) -> String?): String {
    val generic = serverType.isNullOrBlank() || serverType.equals("application/octet-stream", ignoreCase = true)
    if (!generic) return serverType!!
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext.takeIf { it.isNotEmpty() }?.let(byExtension) ?: "application/octet-stream"
}

/** A counter sign-out bumps, so work started before it can tell it is stale. */
internal class DownloadGeneration {
    private val value = AtomicLong()
    fun current(): Long = value.get()
    fun isCurrent(token: Long) = value.get() == token
    fun invalidate() { value.incrementAndGet() }
}

/**
 * What sign-out removes beyond the session itself (WWT-238): downloaded attachments
 * (and any still in flight); Chromium's HTTP disk cache, which holds rendered mail
 * bodies and images and otherwise outlives the WebView — and app restarts; and the
 * WebView's DOM storage, where the mail SPA keeps unsent compose drafts. Discarding
 * the WebView does not clear that: localStorage belongs to the app's WebView profile,
 * so without this the next person to sign in on the phone inherits the draft text.
 * Unsent drafts are therefore lost on sign-out — deliberately (Peter, 2026-09-30).
 * Must run on the main thread: it briefly creates a WebView (clearCache needs an
 * instance, and the cache is shared by every WebView in the process, so this works
 * whether or not mail was opened).
 */
fun purgeSignedOutData(ctx: Context) {
    val app = ctx.applicationContext
    AttachmentDownloads.generation.invalidate()
    Thread { AttachmentStore.of(app).clear() }.start()
    WebStorage.getInstance().deleteAllData()
    // A WebView cannot be created while Android System WebView is mid-update; that
    // must not crash sign-out, which has already started clearing the session.
    runCatching { WebView(ctx).apply { clearCache(true) }.destroy() }
        .onFailure { Log.w("purgeSignedOutData", "Could not clear the WebView cache", it) }
}
