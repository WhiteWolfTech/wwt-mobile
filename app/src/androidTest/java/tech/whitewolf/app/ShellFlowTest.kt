package tech.whitewolf.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class ShellFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsLoginWhenLoggedOut() {
        // Fresh install → not logged in → the login submit button is present.
        compose.onNodeWithTag("submit").assertIsDisplayed()
    }
}
