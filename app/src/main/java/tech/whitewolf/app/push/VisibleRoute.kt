package tech.whitewolf.app.push

import tech.whitewolf.app.subapp.SubAppId

/**
 * Which sub-app is on screen, process-scoped so PushReceiver can read it. The receiver is
 * a manifest-registered BroadcastReceiver with no Activity, so this cannot live in an
 * Activity-scoped ViewModel. Written from the UI thread on route change, read from the
 * receiver's background thread — hence @Volatile. Unlike ForegroundTracker's AtomicInteger
 * (which needs atomic read-modify-write for increment/decrement), here we do single-writer
 * full-reference replacement, so @Volatile suffices.
 *
 * null means the launcher (or nothing) is showing: a wake for any sub-app then notifies.
 */
class VisibleRoute {
    @Volatile
    var current: SubAppId? = null
        private set

    fun set(id: SubAppId?) { current = id }

    fun isVisible(id: SubAppId): Boolean = current == id
}
