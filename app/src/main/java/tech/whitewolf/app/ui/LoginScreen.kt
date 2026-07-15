package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.auth.LoginUiState

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    notice: String? = null,
    onSso: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("White Wolf Technology", style = MaterialTheme.typography.headlineSmall)
        if (notice != null) {
            Text(
                notice,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp).testTag("notice"),
            )
        }
        OutlinedTextField(
            value = state.email, onValueChange = onEmail,
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag("email"),
        )
        OutlinedTextField(
            value = state.password, onValueChange = onPassword,
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("password"),
        )
        if (state.error != null) {
            Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).testTag("error"),
            )
        }
        Button(
            onClick = onSubmit,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("submit"),
        ) { Text("Sign in") }
        if (onSso != null) {
            TextButton(
                onClick = onSso,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("sso"),
            ) { Text("Sign in with WhiteWolf SSO") }
        }
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag("progress"))
        }
    }
}
