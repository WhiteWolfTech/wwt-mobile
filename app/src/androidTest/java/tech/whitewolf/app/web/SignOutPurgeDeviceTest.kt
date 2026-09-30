package tech.whitewolf.app.web

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// WWT-238, on a real device: sign-out removes the WebView's DOM storage, where the
// mail SPA keeps unsent compose drafts (localStorage `wwt.draft.<userId>`), so no
// draft text is left on the phone for the next person.
@RunWith(AndroidJUnit4::class)
class SignOutPurgeDeviceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://wwt238.test/"

    /** Loads a page at [origin] running [script], then returns [probe]'s JS result. */
    private fun runInPage(script: String, probe: String): String {
        val loaded = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var result = ""
        lateinit var wv: WebView
        instr.runOnMainSync {
            wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) = loaded.countDown()
                }
                loadDataWithBaseURL(origin, "<script>$script</script>", "text/html", "utf-8", null)
            }
        }
        assertTrue("page loaded", loaded.await(20, TimeUnit.SECONDS))
        instr.runOnMainSync { wv.evaluateJavascript(probe) { result = it; answered.countDown() } }
        assertTrue("probe answered", answered.await(20, TimeUnit.SECONDS))
        instr.runOnMainSync { wv.destroy() }
        return result
    }

    @Test fun signOutPurgeRemovesStoredDrafts() {
        val stored = runInPage("localStorage.setItem('wwt.draft.1', 'unsent text')", "localStorage.getItem('wwt.draft.1')")
        assertEquals("precondition: the draft is stored", "\"unsent text\"", stored)

        instr.runOnMainSync { purgeSignedOutData(ctx) }

        // Storage deletion is handed to Chromium's storage backend; poll briefly.
        var after = ""
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            after = runInPage("", "localStorage.getItem('wwt.draft.1')")
            if (after == "null") break
            Thread.sleep(250)
        }
        assertEquals("the draft is gone after sign-out", "null", after)
    }
}
