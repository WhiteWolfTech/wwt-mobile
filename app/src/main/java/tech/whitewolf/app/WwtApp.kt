package tech.whitewolf.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import tech.whitewolf.app.push.WakeBus

/**
 * Process root: one shared AppContainer (so receivers/activities don't each build
 * their own dependency graph), the process-scoped WakeBus, and foreground state.
 */
class WwtApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    val wakeBus = WakeBus()

    private val foreground = ForegroundTracker()
    val isForeground: Boolean get() = foreground.isForeground

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foreground.onStart() }
            override fun onActivityStopped(activity: Activity) { foreground.onStop() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        fun from(context: Context): WwtApp = context.applicationContext as WwtApp
    }
}
