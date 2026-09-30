package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAppIdTest {
    @Test fun parseAcceptsLowercaseAlphanumeric() {
        assertEquals(SubAppId("mail"), SubAppId.parse("mail"))
        assertEquals(SubAppId("video2"), SubAppId.parse("video2"))
    }

    // The id is a channel id, a preference key, a URI path segment and a
    // UnifiedPush instance name. Anything that could break one of those is rejected
    // at the boundary rather than corrupting a channel or a deep link later.
    @Test fun parseRejectsValuesUnsafeForItsFiveUses() {
        assertNull(SubAppId.parse(""))
        assertNull(SubAppId.parse("Mail"))
        assertNull(SubAppId.parse("mail/video"))
        assertNull(SubAppId.parse("mail video"))
        assertNull(SubAppId.parse("../etc"))
    }
}
