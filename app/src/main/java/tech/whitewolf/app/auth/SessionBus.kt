package tech.whitewolf.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped truth for "is the shell signed in". Written by [AuthRepository] on
 * login/logout/invalidate (any thread — push registration runs on a background thread)
 * and read by the shell UI, so a token the server has already rejected drops the user
 * to the native login instead of leaving the native TokenStore and the WebView session
 * silently diverged.
 */
class SessionBus(initial: Boolean) {
    private val _loggedIn = MutableStateFlow(initial)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    fun set(v: Boolean) { _loggedIn.value = v }
}
