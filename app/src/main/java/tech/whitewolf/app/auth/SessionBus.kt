package tech.whitewolf.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped truth for "is the shell signed in", plus whether the last sign-out was
 * the server's doing. Written by [AuthRepository] (any thread — push registration runs on
 * a background thread inside a broadcast receiver) and read by the shell UI; StateFlow
 * makes that hand-off safe, same as PushStatusBus.
 *
 * [invalidated] exists so an involuntary logout can explain itself. Being bounced to the
 * login screen for no stated reason reads as a bug; "Your session expired" reads as a
 * session expiring.
 *
 * A deliberate sign-out tears down the session *using* the live bearer (push unregister),
 * and that teardown can itself earn a 401 — the server may have revoked the token already.
 * The user chose to sign out, so they must not be told their session expired.
 * [beginSignOut] gates [invalidate] for the duration. The gate is atomic rather than
 * "raise it, then clear it afterwards", which would leave a window where the UI could
 * observe the raised flag and flash the notice.
 *
 * [invalidate] can be called concurrently from two independent threads: the push
 * registration thread on its own 401, and the sign-out teardown thread. signingOut and
 * invalidated are one invariant spanning two fields, so the check-then-set in [invalidate]
 * must be mutually exclusive with the set-then-clear in [beginSignOut]/[endSignOut] —
 * otherwise an invalidate() that reads signingOut just before beginSignOut() flips it can
 * still raise the flag just after. @Volatile cannot fix that: it makes individual field
 * accesses visible, it does not make the compound operation atomic. The @Synchronized
 * methods lock on this instance, which is sufficient here — the lock is never held across
 * a blocking call.
 */
class SessionBus(initial: Boolean) {
    private val _loggedIn = MutableStateFlow(initial)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    /** True when the session ended because the server rejected our token, rather than
     *  because the user asked. Drives the login screen's notice. */
    private val _invalidated = MutableStateFlow(false)
    val invalidated: StateFlow<Boolean> = _invalidated

    private var signingOut = false

    /** A fresh login: signed in, and nothing left to explain. */
    @Synchronized fun signedIn() {
        _invalidated.value = false
        _loggedIn.value = true
    }

    /** The user asked to leave: signed out, with no "session expired" notice. */
    @Synchronized fun signedOut() {
        _loggedIn.value = false
    }

    /** The server rejected our token: signed out, and the login screen should say why —
     *  unless this 401 came from our own sign-out teardown. */
    @Synchronized fun invalidate() {
        if (!signingOut) _invalidated.value = true
        _loggedIn.value = false
    }

    /** Deliberate sign-out started: 401s from our own teardown are not "session expired". */
    @Synchronized fun beginSignOut() {
        signingOut = true
        _invalidated.value = false
    }

    /** Deliberate sign-out finished: a later 401 is real again. */
    @Synchronized fun endSignOut() {
        signingOut = false
    }
}
