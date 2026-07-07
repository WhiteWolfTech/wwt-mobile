package tech.whitewolf.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.core.app.NotificationManagerCompat
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
import tech.whitewolf.app.net.ConnectivityMonitor
import tech.whitewolf.app.push.Notifications
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
    val retry: () -> Unit = { errored = false; loading = true; reloadKey++ }

    val context = LocalContext.current
    val pushManager = remember { PushManager(context.applicationContext) }
    val pushStatusBus = remember { WwtApp.from(context).pushStatusBus }
    val pushStatus by pushStatusBus.status.collectAsState()
    var notificationsEnabled by remember { mutableStateOf(true) }

    val connectivity = remember { ConnectivityMonitor(context.applicationContext) }
    val online by connectivity.online.collectAsState()
    DisposableEffect(Unit) {
        connectivity.start()
        onDispose { connectivity.stop() }
    }

    // Re-drive push status from the current distributor state; also refresh whether WWT
    // can actually show notifications. Used on entry, resume, and the periodic poll.
    // forceFresh (resume only): in WrongServer, re-register from scratch — ntfy pins a
    // registration to the server that was its default when the registration was created,
    // so a plain register returns the stale endpoint forever after the user fixes the
    // server. unregister+register makes ntfy issue a fresh one against its current server.
    val recheck: (Boolean) -> Unit = { forceFresh ->
        notificationsEnabled = areWwtNotificationsEnabled(context)
        when {
            !pushManager.hasDistributor() -> pushStatusBus.set(PushStatus.NoDistributor)
            forceFresh && pushStatusBus.status.value is PushStatus.WrongServer ->
                pushManager.reregister()
            else -> pushManager.enable()
        }
    }

    LaunchedEffect(Unit) { recheck(false) }

    // Liveness on resume: catches a distributor installed/removed/reconfigured while the
    // app was backgrounded (the common "went to fix it, came back" path).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recheck(true)
                // Reopening the app is the natural "try again" moment: if the
                // error screen is up and we're online, retry without waiting
                // for the 30s tick.
                if (errored && online) retry()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // State-entry trigger for the fresh re-registration: on a cold start the stale
    // endpoint arrives AFTER the resume replay has already run (the process-fresh bus
    // still held Ok at that instant), so the resume path alone never re-registers.
    // Fires once whenever status BECOMES WrongServer; bounded because a still-wrong
    // server returns an equal WrongServer(host) — StateFlow dedupes it and an unchanged
    // key does not restart this effect. The resume trigger still covers "fixed while
    // away", where the value never changes.
    LaunchedEffect(pushStatus) {
        if (pushStatus is PushStatus.WrongServer) pushManager.reregister()
    }

    // Periodic liveness while a problem banner is up and the app is foreground: catches a
    // distributor installed/reconfigured without the app ever backgrounding (e.g.
    // split-screen install). Keyed on isProblem so the loop exists only in a problem
    // state and cancels the moment status reaches Ok; each tick is gated on RESUMED so
    // nothing runs in the background.
    val isProblem = pushStatus !is PushStatus.Ok || !notificationsEnabled
    LaunchedEffect(isProblem) {
        if (!isProblem) return@LaunchedEffect
        while (true) {
            delay(30_000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                recheck(false)
            }
        }
    }

    // Offline-aware auto-retry: a load failure no longer latches forever.
    // Immediate retry when a usable connection (re)appears; every 30s while
    // online (short server blips); never while offline. Each retry waits for
    // RESUMED so nothing reloads from the background. retry() flips `errored`,
    // which restarts this effect — a recurring failure lands back here and
    // waits the full interval, so there is no tight loop.
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(errored, online) {
        val cameOnline = online && !wasOnline
        wasOnline = online
        if (!errored || !online) return@LaunchedEffect
        if (!cameOnline) delay(ERROR_RETRY_MS)
        while (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            delay(ERROR_RETRY_MS)
        }
        retry()
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
            val bannerContent = pushBannerContent(pushStatus, notificationsEnabled)
            if (bannerContent != null) {
                PushStatusBanner(content = bannerContent)
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    errored -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(errorMessageFor(online, subApp.title))
                        Button(
                            onClick = retry,
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

/**
 * True when WWT can actually show notifications: app-level enabled AND the Mail channel
 * not blocked. A channel that doesn't exist yet counts as enabled (it is created on the
 * first notification).
 */
private fun areWwtNotificationsEnabled(context: Context): Boolean {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return false
    val channel = nm.getNotificationChannel(Notifications.CHANNEL_ID)
    return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

private const val ERROR_RETRY_MS = 30_000L

/** Copy for the main-frame load-error screen: offline vs server-unreachable. */
internal fun errorMessageFor(online: Boolean, title: String): String =
    if (online) "Couldn't reach $title."
    else "You're offline. Waiting for a connection…"
