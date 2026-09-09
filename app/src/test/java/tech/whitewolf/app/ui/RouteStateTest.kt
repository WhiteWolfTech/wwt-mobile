package tech.whitewolf.app.ui

import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val mail = SubAppId("mail")
private val video = SubAppId("video")
private val known: (SubAppId) -> Boolean = { it == mail || it == video }

class RouteStateTest {
    @Test fun coldStartOpensTheLastUsedSubApp() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        assertEquals(ShellRoute.Open(mail), s.route.value)
    }

    @Test fun coldStartWithNoHistoryShowsTheLauncher() {
        val s = RouteState(known, lastUsed = null, saved = null)
        assertEquals(ShellRoute.Launcher, s.route.value)
    }

    @Test fun aSavedRouteBeatsLastUsedAfterProcessDeath() {
        val s = RouteState(known, lastUsed = mail, saved = video)
        assertEquals(ShellRoute.Open(video), s.route.value)
    }

    @Test fun anUninstalledLastUsedFallsBackToTheLauncher() {
        // A sub-app removed in a later build must not strand the user on a blank route.
        val s = RouteState(known, lastUsed = SubAppId("gone"), saved = null)
        assertEquals(ShellRoute.Launcher, s.route.value)
    }

    @Test fun aDeepLinkWhileSignedInOpensItsTargetImmediately() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        s.offerLink(WakePayload(video, "v1"), signedIn = true)
        assertEquals(ShellRoute.Open(video), s.route.value)
        assertEquals(WakePayload(video, "v1"), s.pendingLink.value)
    }

    @Test fun aDeepLinkWhileSignedOutIsHeldAndAppliedAfterSignIn() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        s.offerLink(WakePayload(video, "v1"), signedIn = false)
        assertEquals(ShellRoute.Open(mail), s.route.value)  // not yet navigated
        s.onSignedIn()
        assertEquals(ShellRoute.Open(video), s.route.value)
    }

    @Test fun aDeepLinkForAnUnknownSubAppIsIgnored() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        s.offerLink(WakePayload(SubAppId("ghost"), "x"), signedIn = true)
        assertEquals(ShellRoute.Open(mail), s.route.value)
        assertNull(s.pendingLink.value)
    }

    @Test fun consumeClearsThePendingLink() {
        val s = RouteState(known, lastUsed = mail, saved = null)
        val p = WakePayload(video, "v1")
        s.offerLink(p, signedIn = true)
        assertEquals(p, s.consumeLink())
        assertNull(s.pendingLink.value)
    }

    @Test fun openRecordsTheRestoreKey() {
        val s = RouteState(known, lastUsed = null, saved = null)
        s.open(video)
        assertEquals("video", s.restoreKey)
    }
}
