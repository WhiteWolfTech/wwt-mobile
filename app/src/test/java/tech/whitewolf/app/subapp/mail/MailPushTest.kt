package tech.whitewolf.app.subapp.mail

import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import tech.whitewolf.app.push.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailPushTest {
    private val push = MailPush()

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
}
