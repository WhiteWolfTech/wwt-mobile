package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.auth.LoginViewModel
import tech.whitewolf.app.push.PushManager
import tech.whitewolf.app.push.PushStatus
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

    val context = LocalContext.current
    val pushManager = remember { PushManager(context.applicationContext) }
    val pushStatusBus = remember { WwtApp.from(context).pushStatusBus }
    val pushStatus by pushStatusBus.status.collectAsState()

    // Re-drive push status from the current distributor state. Used on first entry, on
    // resume, and from the periodic poll. hasDistributor()/enable() are quick local
    // PackageManager/connector calls (no network); enable() re-registers, and
    // onNewEndpoint then publishes Ok/WrongServer.
    val recheck = {
        if (!pushManager.hasDistributor()) {
            pushStatusBus.set(PushStatus.NoDistributor)
        } else {
            pushManager.enable()
        }
    }

    LaunchedEffect(Unit) { recheck() }

    // Liveness on resume: catches a distributor installed/removed/reconfigured while the
    // app was backgrounded (the common "went to fix it, came back" path).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Periodic liveness while a problem banner is up and the app is foreground: catches a
    // distributor installed/reconfigured without the app ever backgrounding (e.g.
    // split-screen install). Keyed on isProblem so the loop exists only in a problem
    // state and cancels the moment status reaches Ok; each tick is gated on RESUMED so
    // nothing runs in the background.
    val isProblem = pushStatus !is PushStatus.Ok
    LaunchedEffect(isProblem) {
        if (!isProblem) return@LaunchedEffect
        while (true) {
            delay(30_000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                recheck()
            }
        }
    }

    val signOut = {
        val endpoint = container.pushEndpointStore.get()
        pushManager.disable()
        Thread {
            // Order matters: unregister uses the live bearer token, so it must run before
            // logout() clears the token. Not tied to composition, so it survives the
            // screen leaving composition when loggedIn flips.
            if (endpoint != null) container.pushApiClient.unregister(endpoint)
            container.pushEndpointStore.clear()
            container.auth.logout()
        }.start()
        loggedIn = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(subApp.title) },
                actions = { TextButton(onClick = signOut) { Text("Sign out") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val bannerText = pushBannerText(pushStatus)
            if (bannerText != null) {
                PushStatusBanner(
                    text = bannerText,
                    installUrl = if (pushStatus is PushStatus.NoDistributor) NTFY_INSTALL_URL else null,
                )
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    errored -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Couldn't reach ${subApp.title}.")
                        Button(
                            onClick = { errored = false; loading = true; reloadKey++ },
                            modifier = Modifier.padding(top = 12.dp).testTag("retry"),
                        ) { Text("Retry") }
                        Button(
                            onClick = signOut,
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
}
