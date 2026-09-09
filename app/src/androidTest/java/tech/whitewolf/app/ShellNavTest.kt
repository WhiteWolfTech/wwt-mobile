package tech.whitewolf.app

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
}
