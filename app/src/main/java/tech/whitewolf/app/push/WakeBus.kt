package tech.whitewolf.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.whitewolf.app.subapp.SubAppId
import java.util.concurrent.ConcurrentHashMap

/** What to do with a wake-up. */
enum class WakeAction { Foreground, Background }

/**
 * Refresh silently only when the user is actually looking at the sub-app the wake is
 * for. "App is foreground" is not enough once there are two sub-apps: mail arriving
 * while the user watches a video would refresh an off-screen mailbox and tell them
 * nothing.
 */
fun wakeAction(appForeground: Boolean, targetIsVisible: Boolean): WakeAction =
    if (appForeground && targetIsVisible) WakeAction.Foreground else WakeAction.Background

/**
 * Process-scoped, per-sub-app wake signal. Level-triggered ("something changed, refetch")
 * and data-free.
 *
 * There is deliberately no separate `pending` flag. It was consumed only on ON_RESUME,
 * which does not fire when the user reaches a sub-app from the launcher inside an
 * already-resumed Activity — so a notified wake left the target stale. A StateFlow tick
 * covers both arms: a sub-app that is not composed observes the latest value when it next
 * composes, and consumers gate on RESUMED so nothing refreshes from the background.
 */
class WakeBus {
    private val ticks = ConcurrentHashMap<SubAppId, MutableStateFlow<Long>>()

    private fun flow(id: SubAppId): MutableStateFlow<Long> =
        ticks.computeIfAbsent(id) { MutableStateFlow(0L) }

    fun tick(id: SubAppId): StateFlow<Long> = flow(id)

    /** A wake arrived for [id], whether or not it also raised a notification. */
    fun signal(id: SubAppId) {
        val f = flow(id)
        synchronized(f) { f.value = f.value + 1 }
    }
}
