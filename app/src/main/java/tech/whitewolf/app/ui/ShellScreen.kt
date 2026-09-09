package tech.whitewolf.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.auth.LoginViewModel
import tech.whitewolf.app.push.PushManager
import tech.whitewolf.app.subapp.SubAppRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(container: AppContainer) {
    // Signed-in state comes from the session bus, not a local flag: a background 401 (push
    // registration on a token the server has since revoked) invalidates the session, and
    // the shell must fall back to the native login rather than sit on a dead token.
    val loggedIn by container.sessionBus.loggedIn.collectAsState()
    val sessionInvalidated by container.sessionBus.invalidated.collectAsState()

    if (!loggedIn) {
        val vm = remember { LoginViewModel(container.auth, container.ssoLogin) }
        val state by vm.state.collectAsState()
        val ssoLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> vm.onSsoResult(result.data) }
        LoginScreen(
            state, vm::onEmail, vm::onPassword, vm::submit,
            notice = sessionNoticeFor(sessionInvalidated),
            onSso = { vm.startSso { intent -> ssoLauncher.launch(intent) } },
        )
        return
    }

    val context = LocalContext.current
    val vm: ShellViewModel = viewModel(
        factory = ShellViewModelFactory(container, WwtApp.from(context).wakeBus),
    )
    val route by vm.route.collectAsState()

    // Applies a link that arrived while signed out, now that sign-in has completed.
    // Idempotent (RouteState.onSignedIn() is a no-op with nothing held) and scoped to
    // this composition's lifetime as a signed-in user: signing out unmounts this branch
    // entirely (the `!loggedIn` return above), so signing back in re-runs it fresh.
    LaunchedEffect(Unit) { vm.onSignedIn() }

    // Composed BEFORE any sub-app's own Content(): OnBackPressedDispatcher dispatches
    // LIFO, so a sub-app's own handler (mail's history walk today, a future list->player
    // pop) must register LATER than this to win while it's the one on screen.
    BackHandler(enabled = route is ShellRoute.Open) { vm.toLauncher() }

    val pushManager = remember { PushManager(context.applicationContext, container.registry::ids) }
    val pushHealth = rememberPushHealth(container, pushManager)
    val pushStatus = pushHealth.status
    val notificationsEnabled = pushHealth.notificationsEnabled

    // Ask the server whether the stored bearer is still accepted. The local expiry check
    // can say "valid" long after the backend revoked it — a token_version bump invalidates
    // every outstanding session on deploy and nothing tells the shell. A 401 clears the
    // token and flips the session bus, dropping us to the native login on the next frame;
    // a network failure changes nothing (validate() only signs out on an explicit 401).
    // Runs off the main thread: OkHttp on the UI thread would throw.
    val validateSession: () -> Unit = { Thread { container.auth.validate() }.start() }

    LaunchedEffect(Unit) { validateSession() }

    // Liveness on resume: catches a session revoked while we were away. Push health (distributor
    // installed/removed/reconfigured) is handled by rememberPushHealth.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                validateSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Track which sub-app is on screen so PushReceiver can decide whether to notify
    // (sub-app different from the one on screen) or refresh silently (same sub-app).
    // Published on ON_START as well as on route change: clearing on ON_STOP without
    // restoring on ON_START would leave `current` null after any background -> foreground
    // cycle, so every wake for the sub-app actually on screen would notify instead of
    // refreshing silently — a regression on today's behaviour.
    val visible = remember { WwtApp.from(context).visibleRoute }
    DisposableEffect(lifecycleOwner, route) {
        val target = (route as? ShellRoute.Open)?.id
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            visible.set(target)
        }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> visible.set(target)
                Lifecycle.Event.ON_STOP -> visible.set(null)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); visible.set(null) }
    }

    val signOut = {
        // Every registered sub-app's endpoint, captured before teardown starts: each has
        // its own UnifiedPush instance and its own backend registration to drop.
        val endpoints = container.pushEndpointStore.all(container.registry.ids())
        // Gate before the teardown starts: unregister() below uses the live bearer and may
        // earn a 401, but this sign-out is deliberate — no "session expired" notice.
        container.sessionBus.beginSignOut()
        pushManager.disable()
        Thread {
            // Order matters: unregister uses the live bearer token, so it must run before
            // logout() clears the token. Not tied to composition, so it survives the
            // screen leaving composition when loggedIn flips.
            try {
                endpoints.forEach { (id, endpoint) ->
                    container.pushClientFor(id)?.unregister(endpoint)
                    container.pushEndpointStore.clear(id)
                }
                container.auth.logout()
            } finally {
                // Must run even if the teardown throws (EncryptedSharedPreferences can): a
                // gate left raised would suppress every real session-expiry notice from here
                // on, which is worse than the spurious notice it exists to prevent.
                container.sessionBus.endSignOut()
            }
        }.start()
        // Flip the UI now; the thread above clears the token a moment later.
        container.sessionBus.signedOut()
    }

    Scaffold(
        topBar = {
            // Renders on every route, launcher and open alike: when a sub-app cannot
            // load, this Sign out action is the ONLY way out (its own error screen has
            // no sign-out button of its own — see subapp/mail/MailContent.kt).
            TopAppBar(
                title = { Text(titleFor(route, container.registry)) },
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
                when (val r = route) {
                    is ShellRoute.Launcher ->
                        LauncherScreen(container.registry.all(), onOpen = vm::open)

                    is ShellRoute.Open -> {
                        val entry = container.registry.byId(r.id)
                        if (entry == null) {
                            // Do not mutate route state during composition: an unknown id
                            // (e.g. a persisted route from a sub-app a newer build
                            // removed) is corrected as a side effect, not inline here.
                            LaunchedEffect(r.id) { vm.toLauncher() }
                        } else {
                            // Keyed on the sub-app id so switching sub-apps can never
                            // reuse composition state positionally.
                            key(r.id.value) {
                                entry.ui.Content(vm.hostFor(r.id), Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The top bar's title: the suite name at the launcher, the current sub-app's own title
 *  while one is open (falling back to the suite name for the one-frame window before an
 *  unknown route corrects itself to the launcher). */
internal fun titleFor(route: ShellRoute, registry: SubAppRegistry): String = when (route) {
    is ShellRoute.Launcher -> "WWT"
    is ShellRoute.Open -> registry.byId(route.id)?.ui?.title ?: "WWT"
}


/** Copy for the login screen when the server invalidated our token (WWT-57). Null on a
 *  deliberate sign-out — the user knows why they are there. */
internal fun sessionNoticeFor(invalidated: Boolean): String? =
    if (invalidated) "Your session expired. Please sign in again." else null
