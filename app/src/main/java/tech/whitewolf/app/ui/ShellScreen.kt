package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.auth.LoginViewModel
import tech.whitewolf.app.subapp.SubAppRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(container: AppContainer) {
    var loggedIn by remember { mutableStateOf(container.auth.isLoggedIn()) }

    if (!loggedIn) {
        val vm = remember { LoginViewModel(container.auth) }
        val state by vm.state.collectAsState()
        LaunchedEffect(state.loggedIn) { if (state.loggedIn) loggedIn = true }
        LoginScreen(state, vm::onEmail, vm::onPassword, vm::submit)
        return
    }

    val subApp = remember { SubAppRegistry.default() }
    var loading by remember { mutableStateOf(true) }
    var errored by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(subApp.title) },
                actions = { TextButton(onClick = { container.auth.logout(); loggedIn = false }) { Text("Sign out") } },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                errored -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't reach ${subApp.title}.")
                    Button(
                        onClick = { errored = false; loading = true; reloadKey++ },
                        modifier = Modifier.padding(top = 12.dp).testTag("retry"),
                    ) { Text("Retry") }
                    Button(
                        onClick = { container.auth.logout(); loggedIn = false },
                        modifier = Modifier.padding(top = 8.dp).testTag("signout"),
                    ) { Text("Sign out") }
                }
                else -> {
                    key(reloadKey) {
                        SubAppWebView(
                            subApp = subApp,
                            sessionToken = container.auth.currentToken(),
                            onPageError = { errored = true },
                            onPageLoaded = { loading = false },
                        )
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).testTag("progress"),
                        )
                    }
                }
            }
        }
    }
}
