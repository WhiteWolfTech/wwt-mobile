package tech.whitewolf.app.push

import tech.whitewolf.app.mailto.MailtoIntent
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun buildsATargetOnlyLink() {
        assertEquals("wwt://subapp/mail", DeepLink.buildString(WakePayload(SubAppId("mail"))))
    }

    @Test fun buildsAnItemLink() {
        assertEquals(
            "wwt://subapp/video/abc123",
            DeepLink.buildString(WakePayload(SubAppId("video"), "abc123")),
        )
    }

    @Test fun roundTripsBothShapes() {
        assertEquals(WakePayload(SubAppId("mail")), DeepLink.parseString("wwt://subapp/mail"))
        assertEquals(
            WakePayload(SubAppId("video"), "abc123"),
            DeepLink.parseString("wwt://subapp/video/abc123"),
        )
    }

    @Test fun rejectsAnythingItDidNotWrite() {
        assertNull(DeepLink.parseString(null))
        assertNull(DeepLink.parseString("https://mail.whitewolf.tech/inbox"))
        assertNull(DeepLink.parseString("wwt://other/mail"))
        assertNull(DeepLink.parseString("wwt://subapp/"))
        // An id that is unsafe as a channel id or instance name never becomes a target.
        assertNull(DeepLink.parseString("wwt://subapp/Mail"))
        // Reject trailing slash: buildString never emits this.
        assertNull(DeepLink.parseString("wwt://subapp/mail/"))
        // Reject multiple slashes: buildString percent-encodes them, so raw slashes are invalid.
        assertNull(DeepLink.parseString("wwt://subapp/mail/extra/segments"))
    }

    @Test fun itemIdIsPercentEncodedIntoTheWireFormat() {
        assertEquals(
            "wwt://subapp/video/a%20b%2Fc",
            DeepLink.buildString(WakePayload(SubAppId("video"), "a b/c")),
        )
    }

    @Test fun itemIdIsPercentEncodedAndDecoded() {
        val p = WakePayload(SubAppId("video"), "a b/c")
        assertEquals(p, DeepLink.parseString(DeepLink.buildString(p)))
    }

    @Test fun emptyItemIdIsTreatedAsAbsent() {
        assertEquals(
            "wwt://subapp/mail",
            DeepLink.buildString(WakePayload(SubAppId("mail"), "")),
        )
        assertEquals(
            WakePayload(SubAppId("mail")),
            DeepLink.parseString(DeepLink.buildString(WakePayload(SubAppId("mail"), ""))),
        )
    }

    // ---- compose (WWT-253): a mailto: link handed over by MailtoActivity ----

    private val mail = SubAppId("mail")

    // Notification PendingIntent equality compares the data URI, so a compose-less
    // payload must keep building exactly what every notification built before.
    @Test fun aComposeLessPayloadBuildsByteIdenticallyToBefore() {
        assertEquals("wwt://subapp/mail", DeepLink.buildString(WakePayload(mail)))
        assertEquals("wwt://subapp/mail", DeepLink.buildString(WakePayload(mail, null, null)))
        assertEquals("wwt://subapp/video/abc123", DeepLink.buildString(WakePayload(SubAppId("video"), "abc123", null)))
    }

    @Test fun composeIsAQueryParamOnTheTarget() {
        assertEquals(
            "wwt://subapp/mail?compose=mailto%3Aa%40b.com",
            DeepLink.buildString(WakePayload(mail, compose = "mailto:a@b.com")),
        )
    }

    @Test fun composeRoundTripsAwkwardCharacters() {
        val m = "mailto:a@b.com?subject=Caf\u00e9 & co=1%25+x&body=line1%0D%0Aline2 \u2603"
        val p = WakePayload(mail, compose = m)
        assertEquals(p, DeepLink.parseString(DeepLink.buildString(p)))
        val withItem = WakePayload(SubAppId("video"), "a b/c", m)
        assertEquals(withItem, DeepLink.parseString(DeepLink.buildString(withItem)))
    }

    @Test fun oldFormsStillParseWithNoCompose() {
        assertEquals(WakePayload(mail), DeepLink.parseString("wwt://subapp/mail"))
        assertEquals(WakePayload(SubAppId("video"), "abc123"), DeepLink.parseString("wwt://subapp/video/abc123"))
    }

    @Test fun otherQueryParamsAreIgnored() {
        assertEquals(WakePayload(mail), DeepLink.parseString("wwt://subapp/mail?x=1"))
        assertEquals(
            WakePayload(mail, compose = "mailto:a@b.com"),
            DeepLink.parseString("wwt://subapp/mail?x=1&compose=mailto%3Aa%40b.com"),
        )
    }

    // MainActivity is exported, so these are the checks a direct caller cannot skip by
    // going around MailtoActivity. A bad compose is dropped; the rest of the link stands.
    @Test fun anOversizeComposeIsDropped() {
        val big = "mailto:a@b.com?body=" + "x".repeat(MailtoIntent.MAX_LEN)
        assertEquals(WakePayload(mail), DeepLink.parseString(DeepLink.buildString(WakePayload(mail, compose = big))))
    }

    @Test fun aNonMailtoComposeIsDropped() {
        assertEquals(WakePayload(mail), DeepLink.parseString("wwt://subapp/mail?compose=javascript%3Aalert(1)"))
        assertEquals(WakePayload(mail), DeepLink.parseString("wwt://subapp/mail?compose="))
    }

    @Test fun aMalformedComposeIsDroppedNotThrown() {
        assertEquals(WakePayload(mail), DeepLink.parseString("wwt://subapp/mail?compose=mailto%3A%zz"))
    }
}
