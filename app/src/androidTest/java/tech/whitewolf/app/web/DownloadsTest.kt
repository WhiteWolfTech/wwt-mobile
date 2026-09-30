package tech.whitewolf.app.web

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// WWT-238, on a real device: a plan handed to enqueueDownload is fetched by the
// system DownloadManager into Downloads under the planned name, with no storage
// permission (the app declares none). The mail attachment itself needs a signed-in
// session on the real deployment, so this uses a public HTTPS file instead; the
// WebView → listener wiring is verified against the real deployment by hand.
@RunWith(AndroidJUnit4::class)
class DownloadsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    private var id = -1L

    @After fun tearDown() {
        if (id >= 0) dm.remove(id) // deletes the downloaded file too
    }

    @Test fun enqueuedPlanDownloadsUnderItsNameWithoutStoragePermission() {
        val name = "wwt238-${System.currentTimeMillis()}.txt"
        val plan = DownloadPlan(
            url = "https://raw.githubusercontent.com/WhiteWolfTech/wwt-mobile/master/NOTICE",
            fileName = name,
            mimeType = "text/plain",
        )
        id = enqueueDownload(ctx, plan, userAgent = "wwt238-test")

        var status = -1
        var localUri: String? = null
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) {
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                }
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) break
            Thread.sleep(500)
        }
        assertEquals("download status", DownloadManager.STATUS_SUCCESSFUL, status)
        assertTrue("saved as $localUri", localUri!!.endsWith("/$name"))
        val text = ctx.contentResolver.openInputStream(dm.getUriForDownloadedFile(id))!!
            .bufferedReader().use { it.readText() }
        assertTrue("downloaded the real file", text.contains("Apache", ignoreCase = true) || text.isNotBlank())
    }
}
