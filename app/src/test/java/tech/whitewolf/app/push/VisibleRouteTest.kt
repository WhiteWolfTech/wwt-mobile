package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleRouteTest {
    @Test fun nothingIsVisibleBeforeAnySubAppOpens() {
        val v = VisibleRoute()
        assertNull(v.current)
        assertFalse(v.isVisible(SubAppId("mail")))
    }

    @Test fun onlyTheOpenSubAppIsVisible() {
        val v = VisibleRoute()
        v.set(SubAppId("video"))
        assertTrue(v.isVisible(SubAppId("video")))
        assertFalse(v.isVisible(SubAppId("mail")))
    }

    @Test fun theLauncherMakesNothingVisible() {
        // At the launcher a wake for any sub-app should notify, not refresh silently.
        val v = VisibleRoute()
        v.set(SubAppId("mail"))
        v.set(null)
        assertFalse(v.isVisible(SubAppId("mail")))
    }

    @Test fun setAndClearRoundTripsTheCurrentRoute() {
        // This JVM test only verifies that set() round-trips values through current.
        // It does NOT test the ON_START lifecycle behaviour in ShellScreen, because
        // this project has no Robolectric or TestLifecycleOwner on the classpath, and
        // real Lifecycle dispatch through a composable cannot be exercised on the JVM.
        // The ON_START restore (preventing notification regressions after background->
        // foreground cycles) is covered by an instrumented test in ShellNavTest.kt.
        val v = VisibleRoute()
        v.set(SubAppId("mail"))
        v.set(null)
        v.set(SubAppId("mail"))
        assertTrue(v.isVisible(SubAppId("mail")))
    }
}
