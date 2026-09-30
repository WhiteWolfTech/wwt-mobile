package tech.whitewolf.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// WWT-238: the small rules around opening and sign-out, kept off Android APIs.
class AttachmentDownloadRulesTest {
    private val lookup = mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg")::get

    // Senders often label everything application/octet-stream; the extension then
    // decides, so a PDF is offered to PDF viewers.
    @Test fun viewerTypePrefersARealServerType() {
        assertEquals("image/png", viewerMimeType("image/png", "a.pdf", lookup))
    }

    @Test fun viewerTypeFallsBackToTheExtensionForGenericOrMissingTypes() {
        assertEquals("application/pdf", viewerMimeType("application/octet-stream", "report.PDF", lookup))
        assertEquals("application/pdf", viewerMimeType(null, "report.pdf", lookup))
        assertEquals("image/jpeg", viewerMimeType("", "photo.jpg", lookup))
    }

    @Test fun viewerTypeIsOctetStreamWhenNothingIsKnown() {
        assertEquals("application/octet-stream", viewerMimeType("application/octet-stream", "blob", lookup))
        assertEquals("application/octet-stream", viewerMimeType(null, "x.unknownext", lookup))
    }

    // A download that finishes after sign-out belongs to the previous user: the
    // purge must invalidate it so it is discarded rather than kept or opened.
    @Test fun signOutInvalidatesDownloadsStartedBeforeIt() {
        val gen = DownloadGeneration()
        val before = gen.current()
        assertTrue(gen.isCurrent(before))
        gen.invalidate()
        assertFalse(gen.isCurrent(before))
        assertTrue(gen.isCurrent(gen.current()))
    }
}
