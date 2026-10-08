package tech.whitewolf.app.mailto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain inputs only: a Bundle is a default-returning stub in JVM tests
 * (unitTests.isReturnDefaultValues), so a test that went through one would pass
 * vacuously. MailtoActivity's extras reading is a one-line adapter over this.
 */
class MailtoIntentTest {
    private fun mailto(
        data: String?,
        emails: List<String>? = null,
        ccs: List<String>? = null,
        subject: String? = null,
        text: String? = null,
    ) = MailtoIntent.toMailto(data, emails, ccs, subject, text)

    @Test fun dataOnlyPassesThrough() {
        assertEquals("mailto:a@b.com", mailto("mailto:a@b.com"))
        assertEquals("mailto:a@b.com?subject=Hi%20there", mailto("mailto:a@b.com?subject=Hi%20there"))
    }

    // The canonical SENDTO pattern: data is a bare "mailto:" and everything is in extras.
    @Test fun bareMailtoFoldsInEveryExtra() {
        assertEquals(
            "mailto:a%40b.com,c%40d.com?cc=e%40f.com,g%40h.com&subject=Hi&body=Line%201",
            mailto(
                "mailto:",
                emails = listOf("a@b.com", "c@d.com"),
                ccs = listOf("e@f.com", "g@h.com"),
                subject = "Hi",
                text = "Line 1",
            ),
        )
    }

    @Test fun theUrisOwnFieldsWinOverExtras() {
        assertEquals(
            "mailto:x@y.com?Subject=From%20link&cc=e%40f.com&body=b",
            mailto(
                "mailto:x@y.com?Subject=From%20link",
                emails = listOf("ignored@b.com"),
                ccs = listOf("e@f.com"),
                subject = "From extra",
                text = "b",
            ),
        )
    }

    @Test fun aToParamCountsAsTheRecipientField() {
        assertEquals("mailto:?to=x@y.com", mailto("mailto:?to=x@y.com", emails = listOf("a@b.com")))
    }

    @Test fun blankExtrasAreIgnored() {
        assertEquals("mailto:a@b.com", mailto("mailto:a@b.com", ccs = emptyList(), subject = "", text = ""))
    }

    @Test fun notMailtoIsNull() {
        assertNull(mailto(null))
        assertNull(mailto("https://example.com/"))
        assertNull(mailto("tel:123", subject = "x"))
    }

    @Test fun schemeIsCaseInsensitiveAndNormalised() {
        assertEquals("mailto:a@b.com", mailto("MAILTO:a@b.com"))
    }

    @Test fun spacesAreRfc3986EncodedNeverPlus() {
        assertEquals("mailto:?subject=a%20b%2Bc&body=x%26y%3Dz", mailto("mailto:", subject = "a b+c", text = "x&y=z"))
    }

    @Test fun aFragmentIsDropped() {
        assertEquals("mailto:a@b.com?subject=s", mailto("mailto:a@b.com#frag", subject = "s"))
    }

    @Test fun overTheCapDropsTheBody() {
        val big = "x".repeat(MailtoIntent.MAX_LEN)
        assertEquals("mailto:a@b.com?subject=s", mailto("mailto:a@b.com", subject = "s", text = big))
        assertEquals("mailto:a@b.com?subject=s", mailto("mailto:a@b.com?subject=s&body=$big"))
    }

    @Test fun stillOverTheCapIsNull() {
        val big = "x".repeat(MailtoIntent.MAX_LEN)
        assertNull(mailto("mailto:a@b.com", subject = big, text = "body"))
    }
}
