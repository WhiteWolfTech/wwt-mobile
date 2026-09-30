package tech.whitewolf.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// WWT-238: whether the WebView's download listener may fetch an attachment, and under what name.
class DownloadPolicyTest {
    private val host = "mail.whitewolf.tech"
    private val url = "https://mail.whitewolf.tech/api/messages/7/attachments/3"

    private fun plan(
        u: String = url,
        disposition: String? = "attachment; filename=report.pdf",
        mime: String? = "application/pdf",
    ) = DownloadPolicy.plan(u, disposition, mime, host)

    @Test fun inAppAttachmentIsPlanned() {
        assertEquals(DownloadPlan(url, "report.pdf", "application/pdf"), plan())
    }

    @Test fun sharedMailboxAttachmentIsPlanned() {
        val shared = "https://mail.whitewolf.tech/api/mailboxes/13/messages/7/attachments/3"
        assertEquals("report.pdf", plan(u = shared)?.fileName)
    }

    // Exactly the mail origin: the session cookie is host-only, so a subdomain
    // would get no session anyway, and nothing else should be fetched on its behalf.
    @Test fun subdomainIsRefused() {
        assertNull(plan(u = "https://x.mail.whitewolf.tech/api/messages/7/attachments/3"))
    }

    // Only the app's own origin, over HTTPS: a page must not be able to make the
    // app fetch anything else with the user's session cookie attached.
    @Test fun otherHostIsRefused() {
        assertNull(plan(u = "https://evil.example.com/x.pdf"))
        assertNull(plan(u = "https://mail.whitewolf.tech.evil.com/x.pdf"))
        assertNull(plan(u = "https://mail.whitewolf.tech@evil.com/x.pdf"))
    }

    @Test fun nonHttpsIsRefused() {
        assertNull(plan(u = "http://mail.whitewolf.tech/api/messages/7/attachments/3"))
        assertNull(plan(u = "data:application/pdf;base64,AAAA"))
        assertNull(plan(u = "blob:https://mail.whitewolf.tech/1234"))
    }

    // The server writes the disposition with Go's mime.FormatMediaType: quoted
    // when the name has spaces, RFC 2231 filename*= for non-ASCII.
    @Test fun quotedFilename() {
        assertEquals("Q3 report.pdf", plan(disposition = "attachment; filename=\"Q3 report.pdf\"")?.fileName)
    }

    @Test fun rfc2231FilenameIsDecodedAndPreferred() {
        assertEquals(
            "Café menu.pdf",
            plan(disposition = "attachment; filename=\"fallback.pdf\"; filename*=utf-8''Caf%C3%A9%20menu.pdf")?.fileName,
        )
        assertEquals("日本.txt", plan(disposition = "attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC.txt")?.fileName)
    }

    @Test fun inlineDispositionStillNamesTheFile() {
        assertEquals("MSG0001.MP3", plan(disposition = "inline; filename=MSG0001.MP3", mime = "audio/mpeg")?.fileName)
    }

    // The name ends up as a path under Downloads, and it came from a mail sender.
    @Test fun pathAndControlCharactersAreStripped() {
        assertEquals("passwd", plan(disposition = "attachment; filename=\"../../etc/passwd\"")?.fileName)
        assertEquals("x.pdf", plan(disposition = "attachment; filename=\"a\\\\b\\\\x.pdf\"")?.fileName)
        assertEquals("ab.pdf", plan(disposition = "attachment; filename*=utf-8''a%0D%0Ab.pdf")?.fileName)
    }

    @Test fun missingOrUselessNameFallsBack() {
        assertEquals("attachment", plan(disposition = null)?.fileName)
        assertEquals("attachment", plan(disposition = "attachment")?.fileName)
        assertEquals("attachment", plan(disposition = "attachment; filename=\"..\"")?.fileName)
        assertEquals("attachment", plan(disposition = "attachment; filename=\"   \"")?.fileName)
    }

    // Filesystems limit a name to 255 BYTES, not characters.
    @Test fun overlongNameIsTruncatedByBytesKeepingTheExtension() {
        val long = "a".repeat(300) + ".pdf"
        val name = plan(disposition = "attachment; filename=$long")!!.fileName
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun overlongMultibyteNameStaysUnderTheByteLimit() {
        val enc = "%E6%97%A5".repeat(130) // 130 x 日 = 390 bytes
        val name = plan(disposition = "attachment; filename*=utf-8''$enc.txt")!!.fileName
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".txt"))
        assertTrue(name.removeSuffix(".txt").all { it == '日' })
    }

    // A cut must never split a surrogate pair into an invalid string.
    @Test fun truncationKeepsEmojiWhole() {
        val enc = "%F0%9F%93%8E".repeat(60) // 60 x paperclip = 240 bytes
        val name = plan(disposition = "attachment; filename*=utf-8''$enc.pdf")!!.fileName
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.removeSuffix(".pdf").codePoints().allMatch { it == 0x1F4CE })
    }

    // Go's quoted form escapes only " and \.
    @Test fun quotedPairsAreUnescaped() {
        assertEquals("he said \"hi\".pdf", plan(disposition = "attachment; filename=\"he said \\\"hi\\\".pdf\"")?.fileName)
    }

    @Test fun nonUtf8ExtValueFallsBackToPlainFilename() {
        assertEquals("plain.pdf", plan(disposition = "attachment; filename=plain.pdf; filename*=iso-8859-1''caf%E9.pdf")?.fileName)
    }

    @Test fun blankMimeTypeIsNull() {
        assertNull(plan(mime = "")?.mimeType)
        assertNull(plan(mime = null)?.mimeType)
    }
}
