package com.pushengage.pushengage.iam.util

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Remembers which activity is currently resumed, from process start.
 *
 * Android creates manifest ContentProviders before `Application.onCreate()` and
 * before any activity, so registering here sees every lifecycle event. That lets
 * [IAMConfigurationManager] pick up the activity that was already on screen when
 * `PushEngage.Builder().build()` ran late — which is always the case for the
 * React Native and Flutter wrappers, since JS/Dart cannot run until the host
 * activity has resumed, and Android never replays lifecycle callbacks.
 *
 * Declared in the SDK manifest and merged into the host app automatically; host
 * apps configure nothing. If a host strips it (`tools:node="remove"`) nothing
 * breaks — IAM just learns the current activity on the next resume, as before.
 *
 * Holds no IAM logic and does no I/O: it only records a weak reference.
 */
internal class PEActivityTracker : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { install(it) }
        return true
    }

    // Not a data provider — it exists only to run onCreate() early.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        @Volatile
        private var resumed: WeakReference<Activity>? = null

        // Keyed by application instance rather than a plain flag so a new
        // Application (e.g. a fresh Robolectric test) is registered too.
        private var installedOn: WeakReference<Application>? = null

        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        }

        /** Idempotent per application instance. */
        @JvmStatic
        @Synchronized
        fun install(application: Application) {
            if (installedOn?.get() === application) return
            // A different Application means a new process (or test); any activity
            // remembered from before belongs to it and must not be replayed.
            resumed = null
            application.registerActivityLifecycleCallbacks(callbacks)
            installedOn = WeakReference(application)
        }

        /** The activity currently resumed, or null if none (or it is going away). */
        @JvmStatic
        fun resumedActivity(): Activity? {
            val activity = resumed?.get() ?: return null
            if (activity.isFinishing) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) {
                return null
            }
            return activity
        }
    }
}
