package tech.whitewolf.app.subapp.mail

import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import tech.whitewolf.app.push.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailPushTest {
    private val push = MailPush(SubAppId("mail"))

    @Test fun channelIdIsTheSubAppId() {
        assertEquals("mail", push.channelId)
    }

    @Test fun decodesTheBackendsWakeBody() {
        // The mail backend sends exactly this and nothing else — no item id.
        val p = push.decode("""{"type":"new_mail"}""".toByteArray())
        assertEquals(WakePayload(SubAppId("mail"), null), p)
    }

    @Test fun anUnknownBodyDecodesToNull() {
        assertNull(push.decode("""{"type":"something_else"}""".toByteArray()))
        assertNull(push.decode("not json".toByteArray()))
        assertNull(push.decode(ByteArray(0)))
    }

    @Test fun tapTargetIsMailWithNoItem() {
        // Asserts on the payload, not a rebuilt string: the old shape would have passed
        // even if an item id leaked through. android.net.Uri is stubbed in unit tests
        // (isReturnDefaultValues), so SubAppPush exposes a string form for testability
        // and tapUri() is a thin Uri.parse over it.
        assertEquals("wwt://subapp/mail", push.tapTarget(WakePayload(SubAppId("mail"))))
        assertEquals(
            WakePayload(SubAppId("mail"), null),
            DeepLink.parseString(push.tapTarget(WakePayload(SubAppId("mail")))),
        )
    }

    @Test fun tapTargetUsesTheGivenPayloadsIdRatherThanItsOwnConstructorId() {
        // Pins the Finding 2 fix: tapTarget() used to ignore its argument entirely and
        // rebuild a WakePayload from this instance's own private `id` field. That meant
        // a rename that changed MailSubApp's id without also changing whatever id this
        // instance was constructed with would have routing use one id while a tapped
        // notification's target silently used the other. Constructing with a
        // DIFFERENT id than the payload proves tapTarget reads the payload, not `this`.
        val otherId = SubAppId("otherid")
        val p = MailPush(SubAppId("mail"))
        assertEquals("wwt://subapp/$otherId", p.tapTarget(WakePayload(otherId)))
    }
}
