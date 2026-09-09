package tech.whitewolf.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.WakePayload

/**
 * The shell's navigation, as pure state. Android-free so every rule is a JVM test;
 * ShellViewModel is a thin SavedStateHandle wrapper over this.
 *
 * Cold start opens the last-used sub-app so mail stays zero-tap for a mail-only user,
 * but the launcher always sits BENEATH a sub-app — including a deep-linked one — so back
 * needs no special case.
 */
class RouteState(
    private val known: (SubAppId) -> Boolean,
    lastUsed: SubAppId?,
    saved: SubAppId?,
) {
    /** An unknown `saved` falls to [ShellRoute.Launcher] rather than to `lastUsed`: `saved` is this session's actual, more specific route, so a stale one is less trustworthy than no signal at all. */
    private val initial: ShellRoute = (saved ?: lastUsed)
        ?.takeIf(known)
        ?.let { ShellRoute.Open(it) }
        ?: ShellRoute.Launcher

    private val _route = MutableStateFlow(initial)
    val route: StateFlow<ShellRoute> = _route

    private val _pendingLink = MutableStateFlow<WakePayload?>(null)
    val pendingLink: StateFlow<WakePayload?> = _pendingLink

    /** Held while signed out; applied by [onSignedIn]. */
    private var heldLink: WakePayload? = null

    /** The id to persist as "last used", or null at the launcher. */
    val restoreKey: String? get() = (_route.value as? ShellRoute.Open)?.id?.value

    fun open(id: SubAppId) {
        if (known(id)) _route.value = ShellRoute.Open(id)
    }

    fun toLauncher() { _route.value = ShellRoute.Launcher }

    /**
     * A notification tap. Ignored for an unknown sub-app so an older shell survives a
     * newer build's notification. While signed out it is held rather than dropped —
     * the user finishes signing in and lands where they tapped.
     */
    fun offerLink(payload: WakePayload, signedIn: Boolean) {
        if (!known(payload.subAppId)) return
        if (signedIn) {
            _pendingLink.value = payload
            _route.value = ShellRoute.Open(payload.subAppId)
        } else {
            heldLink = payload
        }
    }

    fun onSignedIn() {
        val held = heldLink ?: return
        heldLink = null
        offerLink(held, signedIn = true)
    }

    /** Take the link exactly once — getIntent() re-delivers the same URI after process death. */
    fun consumeLink(): WakePayload? {
        val p = _pendingLink.value
        _pendingLink.value = null
        return p
    }
}
