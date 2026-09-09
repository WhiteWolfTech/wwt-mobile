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

    @Test fun theRouteIsRepublishedAfterBackgrounding() {
        val v = VisibleRoute()
        v.set(SubAppId("mail"))
        v.set(null)                    // ON_STOP
        v.set(SubAppId("mail"))        // ON_START restores it
        assertTrue(v.isVisible(SubAppId("mail")))
    }
}
