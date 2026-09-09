package tech.whitewolf.app.push

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
}
