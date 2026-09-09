package tech.whitewolf.app.subapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow

/**
 * What arrived on the wire. Deliberately thin: a target and an optional item id. Defined
 * here rather than beside SubAppPush because SubAppHost carries it too.
 */
data class WakePayload(val subAppId: SubAppId, val itemId: String? = null)

/**
 * What the shell can say to a sub-app while it is on screen. Three events, settled
 * up front: widening this later is worse than getting it right now.
 */
interface SubAppHost {
    /** "Open this item" — set before the sub-app composes, cleared via [onDeepLinkHandled]. */
    val deepLink: StateFlow<WakePayload?>

    /**
     * "Something changed, refetch" — a monotonic counter, NOT an event stream.
     *
     * It must be level-triggered: a `Flow<Unit>` would either replay on every collect
     * (a spurious refresh each time the user re-enters the sub-app, and again whenever
     * the collecting effect restarts) or drop a tick that arrived while the sub-app was
     * not composed. A counter lets the sub-app remember what it has already seen, which
     * is how the existing `LaunchedEffect(tick, pageLoaded)` already behaves.
     */
    val wake: StateFlow<Long>

    fun onDeepLinkHandled()
}

/**
 * A sub-app as the shell sees it: an id, how to label it, and a screen. Deliberately
 * UI-shaped — no base URL, no token, no HTTP client. A sub-app is CONSTRUCTED with
 * what it needs, so the shell never learns that mail has an origin or that video has
 * a player.
 */
interface SubApp {
    val id: SubAppId
    val title: String
    val icon: ImageVector

    @Composable
    fun Content(host: SubAppHost, modifier: Modifier)
}
