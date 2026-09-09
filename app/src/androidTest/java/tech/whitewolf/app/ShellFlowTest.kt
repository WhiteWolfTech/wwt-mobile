package tech.whitewolf.app

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell's cold-start state on a fresh install.
 *
 * Two things this test needs that are easy to omit, and whose absence produced
 * "No compose hierarchies found in the app" on a real device (BrowserStack, Pixel 8 /
 * Android 14) while passing unnoticed everywhere else — CI runs unit tests only:
 *
 *  - @RunWith(AndroidJUnit4::class), without which the androidx.test rules below do not
 *    drive the Activity lifecycle.
 *  - The POST_NOTIFICATIONS grant, ordered BEFORE the compose rule. MainActivity requests
 *    that permission in onCreate, and on API 33+ the system dialog is a separate window
 *    that covers the app — so the compose rule finds no hierarchy to assert against.
 *    Rule order is explicit: JUnit does not otherwise guarantee it.
 */
@RunWith(AndroidJUnit4::class)
class ShellFlowTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Not a runtime permission below API 33; granting nothing keeps the chain valid.
            GrantPermissionRule.grant()
        }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsLoginWhenLoggedOut() {
        // Fresh install → not logged in → the SSO sign-in button is present.
        compose.onNodeWithTag("sso").assertIsDisplayed()
    }
}
