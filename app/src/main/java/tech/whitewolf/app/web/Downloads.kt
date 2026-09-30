package tech.whitewolf.app.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager

/**
 * Hands a [DownloadPlan] to the system DownloadManager (WWT-238). The request
 * carries the WebView's own cookies for the URL — the session cookie is HttpOnly,
 * which hides it from page JavaScript but not from [CookieManager] — so an
 * authenticated attachment URL downloads as the signed-in user. The file lands in
 * Downloads under the planned name; the system shows progress, and its "complete"
 * notification opens the file. No storage permission is needed: from Android 10
 * (this app's minSdk) DownloadManager may write to the public Downloads directory
 * without one. Returns the download id.
 */
fun enqueueDownload(
    ctx: Context,
    plan: DownloadPlan,
    userAgent: String,
    cookies: CookieManager = CookieManager.getInstance(),
): Long {
    val request = DownloadManager.Request(Uri.parse(plan.url))
        .setTitle(plan.fileName)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, plan.fileName)
        .addRequestHeader("User-Agent", userAgent)
    plan.mimeType?.let(request::setMimeType)
    cookies.getCookie(plan.url)?.let { request.addRequestHeader("Cookie", it) }
    return ctx.getSystemService(DownloadManager::class.java).enqueue(request)
}
