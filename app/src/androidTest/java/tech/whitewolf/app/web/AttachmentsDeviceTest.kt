package tech.whitewolf.app.web

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// WWT-238, on a real device: an attachment is fetched over HTTPS into the app's
// private cache, is reachable only through the app's FileProvider (the URI a viewer
// gets), and sign-out's purge removes it. The mail attachment itself needs a
// signed-in session on the real deployment, so this fetches a public HTTPS file; the
// WebView → listener wiring is verified against the real deployment by hand.
@RunWith(AndroidJUnit4::class)
class AttachmentsDeviceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val store = AttachmentStore.of(ctx)

    @After fun tidy() = store.clear()

    @Test fun fetchedAttachmentIsPrivateSharedByProviderAndPurgedOnSignOut() {
        val file = store.newFile("NOTICE.txt")
        AttachmentFetcher.fetch(
            "https://raw.githubusercontent.com/WhiteWolfTech/wwt-mobile/master/NOTICE",
            cookie = null, userAgent = "wwt238-test", dest = file,
        )
        assertTrue("stored in the private cache", file.canonicalPath.startsWith(ctx.cacheDir.canonicalPath))
        val text = file.readText()
        assertTrue("downloaded the real file", text.contains("WWT", ignoreCase = true) || text.contains("Apache"))

        // What a viewer receives: a content URI from our provider that resolves to
        // the same bytes.
        val uri = FileProvider.getUriForFile(ctx, AttachmentDownloads.authority(ctx), file)
        assertEquals("content", uri.scheme)
        val viaProvider = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(text, viaProvider)

        // Sign-out purge: WebView work must be on the main thread; the file delete
        // runs on a background thread, so wait for it.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { purgeSignedOutData(ctx) }
        val deadline = System.currentTimeMillis() + 5_000
        while (store.dir.listFiles()?.isNotEmpty() == true && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("purge emptied the attachment store", store.dir.listFiles().isNullOrEmpty())
    }
}
