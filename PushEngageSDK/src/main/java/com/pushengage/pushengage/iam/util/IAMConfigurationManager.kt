package com.pushengage.pushengage.iam.util

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RestrictTo
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.controller.IAMControllerFactory
import com.pushengage.pushengage.iam.controller.IAMControllerImpl
import com.pushengage.pushengage.iam.display.IAMDisplayManagerImpl
import java.lang.ref.WeakReference

/**
 * Manages configuration changes and activity lifecycle for In-App Messages.
 * This class ensures that IAMs survive configuration changes and activity recreation.
 *
 * Late initialization: the React Native and Flutter wrappers initialize the SDK from
 * JS/Dart, which cannot run until the host activity has already resumed, and Android
 * never replays lifecycle callbacks — so [initialize] would register into silence and
 * IAM would hold no current activity until some later resume (in-app messages
 * undisplayable on first launch). [initialize] therefore asks [PEActivityTracker],
 * which has been watching since process start, for the already-resumed activity and
 * replays its lifecycle. A native host calling `PushEngage.Builder().build()` from
 * `Application.onCreate()` has no resumed activity yet, so nothing is replayed.
 *
 * Still public, with [RestrictTo], only because released wrapper bridges called
 * [onActivityResumed] directly to work around the above before the tracker existed.
 * Not a supported consumer API; `IAMApiSurfaceTest` allow-lists it. Make it
 * `internal` once no bridge references it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class IAMConfigurationManager private constructor() : Application.ActivityLifecycleCallbacks {
    
    private var displayManager: IAMDisplayManagerImpl? = null
    private var currentActivity: WeakReference<Activity>? = null
    private var lastConfiguration: Configuration? = null

    // Started activities we have SEEN start. Backgrounding is detected when the
    // last of them stops — an ordinary A→B navigation keeps the app foreground
    // because B starts before A stops (guaranteed lifecycle ordering). A set of
    // instances (not a counter) so a stop for an activity that started before
    // this manager registered can't push the count negative. Weak keys: a
    // leaked entry must not pin an activity.
    private val startedActivities: MutableSet<Activity> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    // Whether a resume has been delivered since [initialize]; gates the replay.
    @Volatile
    private var resumedSinceInit = false

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        private var instance: IAMConfigurationManager? = null

        @JvmStatic
        fun getInstance(): IAMConfigurationManager {
            return instance ?: synchronized(this) {
                instance ?: IAMConfigurationManager().also { instance = it }
            }
        }
    }

    /**
     * Initializes the configuration manager with the application context
     * and display manager instance.
     */
    fun initialize(application: Application, displayManager: IAMDisplayManagerImpl) {
        this.displayManager = displayManager
        resumedSinceInit = false
        application.registerActivityLifecycleCallbacks(this)
        PELogger.debug("IAM Configuration Manager initialized")
        replayAlreadyResumedActivity()
    }

    /**
     * Delivers the resume that happened before [initialize] registered — see the
     * class KDoc. Posted to the main thread because wrappers call `build()` from
     * the JS/Dart thread, and re-checked there because the activity may have paused
     * or finished in between. Skipped if a real resume has arrived meanwhile (the
     * callbacks are live by then), so it never double-delivers.
     */
    private fun replayAlreadyResumedActivity() {
        if (PEActivityTracker.resumedActivity() == null) return
        mainHandler.post {
            if (displayManager == null || resumedSinceInit) return@post
            val activity = PEActivityTracker.resumedActivity() ?: return@post
            PELogger.debug("IAM replaying missed resume for ${activity.javaClass.simpleName}")
            // Created first so lastConfiguration is seeded: without it the first
            // rotation of this activity is not detected as a config change and a
            // displayed message stays on the destroyed instance.
            onActivityCreated(activity, null)
            onActivityStarted(activity)
            onActivityResumed(activity)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val currentConfig = activity.resources.configuration
        
        // Check if this is a configuration change
        if (lastConfiguration != null && currentActivity?.get() != null) {
            val configChanged = isConfigurationChanged(lastConfiguration!!, currentConfig)
            if (configChanged) {
                PELogger.debug("Configuration change detected")
                displayManager?.handleConfigurationChange(activity, true)
            }
        }

        // Defensive COPY: activity.resources.configuration is a mutable object the
        // system updates in place on rotation — storing the reference made the
        // "old" config silently become the new one, so the comparison above never
        // detected the change and the message was left attached to the destroyed
        // activity (invisible).
        lastConfiguration = Configuration(currentConfig)
        currentActivity = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = WeakReference(activity)
        startedActivities.add(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedSinceInit = true
        currentActivity = WeakReference(activity)
        
        // Get the controller instance
        val controller = IAMControllerFactory.getInstance()
        if (controller is IAMControllerImpl) {
            // Update the IAMControllerImpl with the new current activity
            controller.updateCurrentActivity(activity)
            
            // Resume the controller to reactivate queue processing and process auto triggers
            controller.resume()
            PELogger.debug("IAM Controller resumed for activity ${activity.javaClass.simpleName}")
            
            // Log that activity was set as current
            PELogger.debug("Activity ${activity.javaClass.simpleName} resumed and set as current for IAM")
        }
        
        // Resume the display manager when the app comes to the foreground
        displayManager?.resume()
        PELogger.debug("IAM Display Manager resumed due to activity ${activity.javaClass.simpleName} resuming")
    }

    override fun onActivityPaused(activity: Activity) {
        // Deliberately a no-op: an ordinary A→B navigation pauses A while the
        // app stays foreground (B's resume comes after A's pause), so pausing/
        // dismissing here consumed the message and recorded a false
        // "app_backgrounded" dismiss on every screen change. Real backgrounding
        // is detected in onActivityStopped, when the LAST started activity stops.
    }

    override fun onActivityStopped(activity: Activity) {
        // A rotation/config change relaunches the activity: it stops with no
        // successor started yet. Pausing then would DISMISS the displayed
        // message ("app_backgrounded"), completing it and advancing the queue —
        // a rotation must not consume a message. The re-attach to the new
        // activity is handled by onActivityCreated → handleConfigurationChange.
        if (activity.isChangingConfigurations) {
            PELogger.debug("IAM pause skipped: ${activity.javaClass.simpleName} is changing configurations")
            return
        }

        startedActivities.remove(activity)
        if (startedActivities.isNotEmpty()) {
            // Another activity is still started — this stop is part of an
            // in-app navigation, not a backgrounding.
            return
        }

        // Get the controller instance
        val controller = IAMControllerFactory.getInstance()
        if (controller is IAMControllerImpl) {
            // Pause the controller to stop queue processing
            controller.pause()
            PELogger.debug("IAM Controller paused: app went to background from ${activity.javaClass.simpleName}")
        }

        // Pause the display manager when the app goes to the background
        displayManager?.pause()
        PELogger.debug("IAM Display Manager paused: app went to background from ${activity.javaClass.simpleName}")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // No-op
    }

    override fun onActivityDestroyed(activity: Activity) {
        // The only place a config-changing activity leaves [startedActivities].
        // onActivityStopped returns early for it — pausing mid-rotation would
        // dismiss the displayed message — which left the entry to be reclaimed
        // whenever GC happened to collect the weak key. Until then the set looked
        // non-empty, so the next real stop read as an in-app navigation and IAM
        // stayed "foregrounded" with nothing on screen: the queue kept processing
        // and the auto-dismiss and trigger-delay timers kept running. Rotate, then
        // background before that GC, and the app was in that state.
        //
        // Safe here because a configuration change destroys the old activity
        // before creating the new one, so the set is empty in between and the new
        // activity re-adds itself in onActivityStarted. A plain finish() has
        // already been removed by onActivityStopped, making this a no-op.
        startedActivities.remove(activity)

        if (currentActivity?.get() == activity) {
            currentActivity = null
        }
        // Keep the controller's current-activity reference in sync so it never
        // points at (and never displays into) a destroyed activity.
        val controller = IAMControllerFactory.getInstance()
        if (controller is IAMControllerImpl) {
            controller.onActivityDestroyed(activity)
        }
    }

    /**
     * Checks if there was a relevant configuration change between old and new configs
     */
    private fun isConfigurationChanged(oldConfig: Configuration, newConfig: Configuration): Boolean {
        return oldConfig.orientation != newConfig.orientation ||
                oldConfig.screenWidthDp != newConfig.screenWidthDp ||
                oldConfig.screenHeightDp != newConfig.screenHeightDp
    }

    /**
     * Cleans up resources and unregisters callbacks
     */
    fun cleanup(application: Application) {
        application.unregisterActivityLifecycleCallbacks(this)
        displayManager = null
        currentActivity = null
        lastConfiguration = null
        startedActivities.clear()
        instance = null
        PELogger.debug("IAM Configuration Manager cleaned up")
    }
} 