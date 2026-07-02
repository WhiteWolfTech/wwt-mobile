package tech.whitewolf.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What to do with a wake-up, decided purely from the app's foreground state. */
enum class WakeAction { Foreground, Background }

/** Foreground → refresh live (bump tick); background → notify + pending. */
fun wakeAction(foreground: Boolean): WakeAction =
    if (foreground) WakeAction.Foreground else WakeAction.Background

/**
 * Process-scoped wake signal. Level-triggered ("something changed, refetch") and
 * data-free. `tick` drives a live refresh while the app is foregrounded; `pending`
 * carries a wake that arrived while backgrounded until the next foreground resume.
 * In-memory only — never persisted (a dead process cold-starts fresh anyway).
 */
class WakeBus {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    @Volatile private var pending = false

    /** Foreground wake: refresh now. Bumps the tick; sets no pending. */
    fun signalWakeForeground() { _tick.value = _tick.value + 1 }

    /** Background wake: refresh on return. Sets pending; does not bump the tick. */
    @Synchronized fun signalWakeBackground() { pending = true }

    /** Returns whether a background wake is pending, clearing it. */
    @Synchronized fun consumePending(): Boolean {
        val p = pending
        pending = false
        return p
    }
}
