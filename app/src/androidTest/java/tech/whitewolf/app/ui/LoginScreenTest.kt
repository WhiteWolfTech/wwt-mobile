package tech.whitewolf.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.whitewolf.app.auth.LoginUiState

class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsErrorAndFiresSubmit() {
        var submitted = false
        compose.setContent {
            LoginScreen(
                state = LoginUiState(error = "Incorrect email or password"),
                onEmail = {}, onPassword = {}, onSubmit = { submitted = true },
            )
        }
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithTag("submit").performClick()
        assertTrue(submitted)
    }

    @Test fun disablesSubmitWhileLoading() {
        compose.setContent {
            LoginScreen(LoginUiState(loading = true), {}, {}, {})
        }
        compose.onNodeWithTag("progress").assertIsDisplayed()
        compose.onNodeWithTag("submit").assertIsNotEnabled()
    }
}
