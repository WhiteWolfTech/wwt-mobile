package tech.whitewolf.app.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// WWT-238: downloaded attachments live in app-private storage and are tidied up —
// everything on sign-out, anything over a week old at app start.
class AttachmentStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir by lazy { File(tmp.root, "attachments") }
    private val store by lazy { AttachmentStore(dir) }

    @Test fun newFileIsInsideTheStoreUnderItsName() {
        val f = store.newFile("report.pdf")
        assertEquals(dir.canonicalFile, f.parentFile!!.canonicalFile)
        assertEquals("report.pdf", f.name)
        assertFalse("reserving a name must not create an empty file", f.exists())
    }

    @Test fun collidingNamesGetASuffixBeforeTheExtension() {
        store.newFile("report.pdf").writeText("1")
        val second = store.newFile("report.pdf").also { it.writeText("2") }
        val third = store.newFile("report.pdf")
        assertEquals("report-1.pdf", second.name)
        assertEquals("report-2.pdf", third.name)
        store.newFile("README").writeText("x")
        assertEquals("README-1", store.newFile("README").name)
    }

    @Test fun pruneRemovesOnlyFilesOlderThanTheLimit() {
        val now = 1_000_000_000_000L
        val week = 7L * 24 * 60 * 60 * 1000
        val old = store.newFile("old.pdf").also { it.writeText("o"); it.setLastModified(now - week - 1) }
        val fresh = store.newFile("fresh.pdf").also { it.writeText("f"); it.setLastModified(now - week + 60_000) }
        assertEquals(1, store.pruneOlderThan(now, week))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun clearRemovesEverythingIncludingPartialDownloads() {
        store.newFile("a.pdf").writeText("a")
        File(dir, "b.pdf.part").writeText("partial")
        store.clear()
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun pruneAndClearTolerateAMissingDirectory() {
        assertEquals(0, store.pruneOlderThan(System.currentTimeMillis(), 1000))
        store.clear() // no throw
    }
}
