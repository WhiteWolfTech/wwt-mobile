package tech.whitewolf.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.WwtApp
import tech.whitewolf.app.push.Notifications
import tech.whitewolf.app.push.PushManager
import tech.whitewolf.app.push.PushStatus

/** Health of push notifications for the shell: whether the distributor is healthy and
 *  whether WWT can show them. */
data class PushHealth(
    val status: PushStatus,
    val notificationsEnabled: Boolean,
)

/**
 * Compose state for push health. Synthesizes PushStatus from the distributor + re-registers
 * on server mismatch (the "forceFresh" path), and tracks notification enablement.
 * Polls every 30 seconds while a problem banner would be up and the app is foreground.
 */
@Composable
fun rememberPushHealth(container: AppContainer, pushManager: PushManager): PushHealth {
    val context = LocalContext.current
    val pushStatusBus = remember { WwtApp.from(context).pushStatusBus }
    val pushStatus by pushStatusBus.status.collectAsState()
    var notificationsEnabled by remember { mutableStateOf(true) }

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

    return PushHealth(pushStatus, notificationsEnabled)
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

/**
 * Notifications count as blocked when any REGISTERED sub-app's channel is blocked. The
 * old check looked at one hardcoded channel; with a channel per sub-app, a channel left
 * behind by a removed sub-app must not raise a banner forever.
 */
internal fun channelsBlocked(blocked: Set<String>, registered: List<String>): Boolean =
    registered.any { it in blocked }
