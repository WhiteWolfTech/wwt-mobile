package tech.whitewolf.app

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.whitewolf.app.subapp.Retained
import tech.whitewolf.app.subapp.SubAppId

/**
 * The route switch this task wires back up: the launcher shows one tile per registered
 * sub-app, and tapping one opens it.
 *
 * NOT createAndroidComposeRule<MainActivity>(): that rule launches the Activity as part of
 * applying itself, before @Before runs, leaving no window in which to clear prefs or seed a
 * session. createEmptyComposeRule() plus an explicit ActivityScenario.launch() inside each
 * @Test keeps @Before in charge of state before anything is on screen.
 *
 * A signed-in session is seeded directly on the process-scoped AppContainer's SessionBus —
 * the same in-process seeding scripts/browserstack-espresso.sh's own comment names as the
 * only way to get a signed-in session under instrumentation, since driving the real login
 * flow would need live backend credentials this suite does not have. @After signs back out
 * so a sibling test class in the same instrumentation process (ordering across classes is
 * not guaranteed) does not inherit a signed-in session — ShellFlowTest.showsLoginWhenLoggedOut
 * assumes a fresh, logged-out install.
 *
 * The permission rule mirrors ShellFlowTest's: on API 33+, MainActivity's own
 * POST_NOTIFICATIONS request opens a system dialog in a separate window, which leaves the
 * compose rule with no hierarchy to assert against unless the permission is already granted
 * before the Activity launches.
 */
@RunWith(AndroidJUnit4::class)
class ShellNavTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Not a runtime permission below API 33; granting nothing keeps the chain valid.
            GrantPermissionRule.grant()
        }

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Before fun reset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The tap test persists shell.lastUsed = mail, which would make the next cold
        // start open mail directly and hide the launcher. Tests must not depend on order.
        context.getSharedPreferences("wwt.shell", Context.MODE_PRIVATE)
            .edit().clear().commit()
        WwtApp.from(context).container.sessionBus.signedIn()
    }

    @After fun signOutAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WwtApp.from(context).container.sessionBus.signedOut()
    }

    @Test fun launcherShowsATilePerRegisteredSubApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("tile.mail").assertIsDisplayed()
        }
    }

    @Test fun tappingATileOpensThatSubApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("tile.mail").performClick()
            compose.onNodeWithTag("subapp.mail").assertIsDisplayed()
        }
    }

    @Test fun signOutDiscardsRetainedMailStateSoTheNextEntryIsRebuilt() {
        // Finding 1 (2026-09-08 final review): SubAppScopes.discardAll() had zero
        // production callers, so signing out left the previous session's WebView (DOM,
        // localStorage) retained and re-attached verbatim on the next sign-in. The fix
        // wires discardAll() into ShellScreen's signOut lambda. This asserts the actual
        // observable contract that matters: the retained mail scope is a DIFFERENT
        // instance after a sign-out/sign-in round trip, i.e. it was rebuilt, not reused.
        //
        // scopes.getOrPut(id) { error(...) } is a safe, non-invasive way to PEEK at
        // whatever is already retained without creating anything: the create lambda
        // only runs when nothing is held. It only throws if mail's content has not
        // actually composed (and retained a scope) by the point it is called, which
        // both call sites below arrange for by asserting "subapp.mail" is displayed
        // immediately before peeking.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scopes = WwtApp.from(context).container.scopes
        val mailId = SubAppId("mail")

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("tile.mail").performClick()
            compose.onNodeWithTag("subapp.mail").assertIsDisplayed()
            val before: Retained =
                scopes.getOrPut(mailId) { error("mail scope should already be retained") }

            compose.onNodeWithText("Sign out").performClick()
            // Confirms sign-out actually took effect (not just that discardAll() ran)
            // before asserting anything about scopes: the login screen replacing the
            // shell is the visible half of the same loggedIn flip that triggers discard.
            compose.onNodeWithTag("email").assertIsDisplayed()

            // Sign back in the same way @Before does: this suite has no live backend
            // credentials to drive the real SSO/password flow. The ShellViewModel
            // instance (and its route) survives the round trip — it is scoped to the
            // Activity, not to the signed-in composition — so route stays Open(mail)
            // and mail's Content() recomposes without another tile tap.
            WwtApp.from(context).container.sessionBus.signedIn()
            compose.onNodeWithTag("subapp.mail").assertIsDisplayed()
            val after: Retained =
                scopes.getOrPut(mailId) { error("mail scope should have been rebuilt by now") }

            assertNotSame(
                "sign-out must discard the retained mail scope so re-entry rebuilds it, " +
                    "not re-attach the previous session's WebView",
                before,
                after,
            )
        }
    }

    @Test fun visibleRouteIsRepublishedOnResumeAfterBackgrounding() {
        // Tests the ON_START restore that prevents notification regressions: after
        // backgrounding a sub-app, resuming should republish its route so a wake for
        // that sub-app refreshes silently instead of notifying.
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Open mail, assert route is published
            compose.onNodeWithTag("tile.mail").performClick()
            compose.onNodeWithTag("subapp.mail").assertIsDisplayed()
            val mailId = SubAppId("mail")
            assertTrue(
                "mail route should be visible when open",
                WwtApp.from(context).visibleRoute.isVisible(mailId)
            )

            // Background the activity (moves through ON_STOP), assert current cleared
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(
                "current should be null after backgrounding",
                WwtApp.from(context).visibleRoute.isVisible(mailId)
            )

            // Resume (moves through ON_START), assert route republished
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(
                "mail route should be republished on resume",
                WwtApp.from(context).visibleRoute.isVisible(mailId)
            )
        }
    }
}
