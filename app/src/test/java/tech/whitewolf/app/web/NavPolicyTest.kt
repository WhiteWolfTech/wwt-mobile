package tech.whitewolf.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPolicyTest {
    private val host = "mail.whitewolf.tech"

    @Test fun sameHostHttpsIsInApp() {
        assertTrue(NavPolicy.isInApp("https://mail.whitewolf.tech/inbox", host))
    }
    @Test fun subdomainIsInApp() {
        assertTrue(NavPolicy.isInApp("https://sub.mail.whitewolf.tech/x", host))
    }
    @Test fun differentHostIsExternal() {
        assertFalse(NavPolicy.isInApp("https://evil.example.com/x", host))
    }
    @Test fun suffixTrickIsExternal() {
        assertFalse(NavPolicy.isInApp("https://mail.whitewolf.tech.evil.com/x", host))
    }
    @Test fun nonHttpsIsExternal() {
        assertFalse(NavPolicy.isInApp("http://mail.whitewolf.tech/x", host))
        assertFalse(NavPolicy.isInApp("mailto:a@b.com", host))
    }
    @Test fun userinfoBypassIsExternal() {
        // https://<allowed>@evil.com/ — userinfo must not be mistaken for the host
        assertFalse(NavPolicy.isInApp("https://mail.whitewolf.tech@evil.com/", host))
    }
    @Test fun uppercaseHostIsInApp() {
        assertTrue(NavPolicy.isInApp("https://MAIL.WHITEWOLF.TECH/inbox", host))
    }
    @Test fun trailingDotHostIsInApp() {
        assertTrue(NavPolicy.isInApp("https://mail.whitewolf.tech./inbox", host))
    }
}
