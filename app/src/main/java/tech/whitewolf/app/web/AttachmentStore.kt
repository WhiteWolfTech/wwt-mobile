package tech.whitewolf.app.web

import android.content.Context
import java.io.File

/**
 * Where downloaded mail attachments live (WWT-238): the app's private cache, never
 * the shared Downloads collection — no other app can list them, and they are tidied
 * up rather than accumulating. Everything goes on sign-out ([clear]); anything older
 * than a week goes at app start ([pruneOlderThan]); Android may also evict the cache
 * under storage pressure. A file leaves the app only through a one-off read grant
 * when the user opens it (see [openAttachment]).
 *
 * Plain java.io over [dir], so the rules are testable on the JVM.
 */
class AttachmentStore(val dir: File) {

    /**
     * A not-yet-existing file in the store for [name] (already sanitised by
     * [DownloadPolicy]); a clash gets "-1", "-2"… before the extension, so an earlier
     * attachment of the same name is never overwritten while a viewer may hold it.
     */
    fun newFile(name: String): File {
        dir.mkdirs()
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$stem-${n++}$ext")
        return candidate
    }

    /** Deletes files last modified more than [maxAgeMs] before [nowMs]; returns how many. */
    fun pruneOlderThan(nowMs: Long, maxAgeMs: Long): Int =
        dir.listFiles().orEmpty().count { it.lastModified() < nowMs - maxAgeMs && it.delete() }

    /** Deletes every file in the store, partial downloads included. */
    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun of(ctx: Context) = AttachmentStore(File(ctx.cacheDir, "attachments"))
    }
}
