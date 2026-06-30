package tech.whitewolf.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetCookieTest {
    @Test fun picksTheSessionCookieHeader() {
        val headers = listOf(
            "other=abc; Path=/",
            "session=u.exp.sig; Path=/; HttpOnly; Secure; SameSite=Lax",
        )
        assertEquals(
            "session=u.exp.sig; Path=/; HttpOnly; Secure; SameSite=Lax",
            sessionCookieFrom(headers),
        )
    }

    @Test fun returnsNullWhenNoSessionCookie() {
        assertNull(sessionCookieFrom(listOf("foo=1; Path=/")))
        assertNull(sessionCookieFrom(emptyList()))
    }

    @Test fun matchesSessionNameNotSubstring() {
        // "sessionx=" must NOT be treated as the session cookie.
        assertNull(sessionCookieFrom(listOf("sessionx=nope; Path=/")))
    }
}
