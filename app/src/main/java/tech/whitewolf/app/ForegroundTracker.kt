package tech.whitewolf.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts started activities to answer "is the app in the foreground?". Read from a
 * background thread (PushReceiver), so backed by an atomic counter.
 */
class ForegroundTracker {
    private val started = AtomicInteger(0)
    val isForeground: Boolean get() = started.get() > 0
    fun onStart() { started.incrementAndGet() }
    fun onStop() { started.decrementAndGet() }
}
