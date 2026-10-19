package co.aura.actions

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks application foreground state across all activities to reliably detect
 * when JARVIS is in the background subject to Android 10+ (API 29+) Background Activity Launch restrictions.
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    @Volatile
    var activeActivityCount: Int = 0
        private set

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        activeActivityCount++
        isAppInForeground = true
    }

    override fun onActivityResumed(activity: Activity) {
        isAppInForeground = true
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        activeActivityCount = (activeActivityCount - 1).coerceAtLeast(0)
        if (activeActivityCount == 0) {
            isAppInForeground = false
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
