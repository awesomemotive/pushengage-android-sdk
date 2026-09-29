package com.pushengage.pushengage.iam.rules

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.pushengage.pushengage.helper.PELogger
import java.util.Locale
import java.util.TimeZone

/**
 * Manager for user attributes used in audience targeting rules
 * This implementation matches the iOS approach
 */
internal class IAMDevicePropertiesManager private constructor(context: Context) {
    
    // Use applicationContext to avoid memory leaks
    private val appContext: Context = context.applicationContext
    
    private val preferences: SharedPreferences = appContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE
    )
    
    /**
     * Sets a user attribute value
     * @param key Property key
     * @param value Property value
     */
    fun setUserAttribute(key: String, value: String) {
        val prefKey = USER_ATTRIBUTE_PREFIX + key
        preferences.edit().putString(prefKey, value).apply()
        PELogger.debug("Custom property set: $key = $value")
    }
    
    /**
     * Gets a user attribute value
     * @param key The field to get the value for
     * @return The value as a string, or null if not found
     */
    fun getUserAttribute(key: String): String? {
        val prefKey = USER_ATTRIBUTE_PREFIX + key
        
        // First check custom attributes in SharedPreferences
        var value = preferences.getString(prefKey, null)
        
        // If not found in preferences, check system attributes
        if (value == null) {
            value = getSystemAttribute(key)
        }
        
        return value
    }
    
    /**
     * Gets a system attribute value
     * @param key The system attribute to get
     * @return The value as a string, or null if not available
     */
    private fun getSystemAttribute(key: String): String? {
        return when (key) {
            "platform" -> "Android"
            // Marketing version ("14", "8.1.0"), NOT the API level. Matches the
            // subscriber record's `device_version` and iOS's systemVersion, so one
            // audience vocabulary works across both platforms. Dotted values mean
            // this is compared component-wise, never as a plain number.
            "os_version" -> Build.VERSION.RELEASE
            // Deliberately no `device_model`. Joining MANUFACTURER and MODEL yields
            // opaque part numbers such as "samsung SM-G991B", and MANUFACTURER casing
            // varies by OEM ("samsung" vs "Google"), so exact matching is unreliable
            // and the value is not something a campaign author can type. Left
            // unresolvable so such a condition fails closed rather than mis-targeting.
            "language" -> Locale.getDefault().language
            // Device locale region, deliberately NOT named `country`: the backend
            // also has an IP-derived country, and the two genuinely disagree (a
            // device with a US locale on an Indian IP reports both). `country` is
            // left unresolvable so such a condition fails closed rather than
            // silently matching the wrong one.
            "device_region" -> Locale.getDefault().country
            "timezone" -> TimeZone.getDefault().id
            "app_version" -> hostAppVersionName()
            "notification_enabled" ->
                NotificationManagerCompat.from(appContext).areNotificationsEnabled().toString()
            else -> null
        }
    }

    /** Host app's `versionName`, or null when it cannot be read. */
    private fun hostAppVersionName(): String? {
        return try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        } catch (e: Exception) {
            PELogger.error("Could not read host app version: ${e.message}", e)
            null
        }
    }
    
    /**
     * Replaces the cached subscriber-backed audience data with what the backend
     * returned. Cached so it survives process death — the fetch runs once per app
     * open, and evaluating against the previous snapshot beats evaluating against
     * nothing.
     *
     * @param segments the subscriber's segment names (set-valued)
     * @param attributes custom attributes; stored under the `attr.` namespace so a
     *   marketer-defined key such as `plan` can never shadow a built-in field
     * @param scalars subscriber-backed scalars keyed by their audience field name
     *   (`city`, `state`, `geo_country`, `has_unsubscribed`)
     * @param identity the subscriber this snapshot describes, stored alongside it so a
     *   later identity change can tell that the snapshot belongs to someone else
     */
    fun applySubscriberState(
        segments: Set<String>,
        attributes: Map<String, String>,
        scalars: Map<String, String>,
        identity: String
    ) {
        val editor = preferences.edit()

        // Replace rather than merge: anything the new snapshot omits must disappear,
        // or a stale value outlives the data it came from — a subscriber who moves keeps
        // their old city, and a site that disables geo keeps geo forever.
        preferences.all.keys
            .filter { it.startsWith(USER_ATTRIBUTE_PREFIX + ATTRIBUTE_NAMESPACE) }
            .forEach { editor.remove(it) }

        // Scalars are stored under bare field names, so they cannot be found by prefix.
        // The keys written last time are remembered instead of hard-coding a list here,
        // which would duplicate the field catalogue and be free to drift from it.
        preferences.getStringSet(SCALAR_KEYS_KEY, emptySet())?.forEach {
            editor.remove(USER_ATTRIBUTE_PREFIX + it)
        }

        editor.putStringSet(SEGMENTS_KEY, segments)
        editor.putStringSet(SCALAR_KEYS_KEY, scalars.keys)
        attributes.forEach { (key, value) ->
            editor.putString(USER_ATTRIBUTE_PREFIX + ATTRIBUTE_NAMESPACE + key, value)
        }
        scalars.forEach { (key, value) ->
            editor.putString(USER_ATTRIBUTE_PREFIX + key, value)
        }
        editor.putString(SUBSCRIBER_IDENTITY_KEY, identity)
        editor.putBoolean(SUBSCRIBER_STATE_LOADED_KEY, true)
        editor.apply()

        PELogger.debug(
            "IAM subscriber state applied: ${segments.size} segment(s), " +
                    "${attributes.size} attribute(s), ${scalars.size} scalar(s)"
        )
    }

    /**
     * Discards the cached snapshot when it belongs to a different subscriber.
     *
     * A re-subscribe issues a new hash and unsubscribe clears it. Without this the
     * snapshot keeps answering with the previous subscriber's segments and attributes
     * — and because [SUBSCRIBER_STATE_LOADED_KEY] stays set, deferral does not engage
     * to stop it. Only a successful fetch would otherwise replace the data, so a
     * failed or absent fetch leaves the wrong person's targeting in place indefinitely.
     */
    fun invalidateSubscriberStateIfIdentityChanged(identity: String) {
        val stored = preferences.getString(SUBSCRIBER_IDENTITY_KEY, "") ?: ""
        if (stored == identity) return

        // Nothing cached yet: record who we are now, with nothing to discard.
        if (!hasSubscriberState() && stored.isEmpty()) {
            preferences.edit().putString(SUBSCRIBER_IDENTITY_KEY, identity).apply()
            return
        }

        PELogger.debug("IAM subscriber identity changed — discarding the cached snapshot")
        clearSubscriberState(identity)
    }

    /**
     * Removes everything the subscriber fetch contributed, leaving the state reading as
     * never-fetched so subscriber-backed conditions defer.
     *
     * Locally set attributes are deliberately untouched: they are the app's own state
     * and live outside the `attr.` namespace.
     */
    fun clearSubscriberState(identity: String = "") {
        val editor = preferences.edit()

        preferences.all.keys
            .filter { it.startsWith(USER_ATTRIBUTE_PREFIX + ATTRIBUTE_NAMESPACE) }
            .forEach { editor.remove(it) }

        preferences.getStringSet(SCALAR_KEYS_KEY, emptySet())?.forEach {
            editor.remove(USER_ATTRIBUTE_PREFIX + it)
        }

        editor.remove(SCALAR_KEYS_KEY)
        editor.remove(SEGMENTS_KEY)
        editor.remove(SUBSCRIBER_STATE_LOADED_KEY)
        editor.putString(SUBSCRIBER_IDENTITY_KEY, identity)
        editor.apply()
    }

    /**
     * Whether subscriber-backed data has ever been fetched successfully.
     *
     * Audience conditions on subscriber-backed fields are **deferred** rather than
     * failed while this is false: failing closed would mean such a campaign never
     * shows on a fresh install, and failing *open* on a negative operator would show
     * it to everyone.
     */
    fun hasSubscriberState(): Boolean = preferences.getBoolean(SUBSCRIBER_STATE_LOADED_KEY, false)

    /** The subscriber's segments, empty when none are cached. */
    fun getSegments(): Set<String> = preferences.getStringSet(SEGMENTS_KEY, emptySet()) ?: emptySet()

    /**
     * Removes a user attribute
     * @param key Property key to remove
     */
    fun removeUserAttribute(key: String) {
        val prefKey = USER_ATTRIBUTE_PREFIX + key
        preferences.edit().remove(prefKey).apply()
        PELogger.debug("Custom property removed: $key")
    }
    
    /**
     * Clears all custom properties
     */
    fun clearUserAttributes() {
        val allPrefs = preferences.all
        val editor = preferences.edit()
        
        for (key in allPrefs.keys) {
            if (key.startsWith(USER_ATTRIBUTE_PREFIX)) {
                editor.remove(key)
            }
        }
        
        editor.apply()
        PELogger.debug("All custom properties cleared")
    }
    
    companion object {
        private const val PREFERENCES_NAME = "pe_iam_user_attributes"
        private const val USER_ATTRIBUTE_PREFIX = "pe_user_attribute_"

        /**
         * Namespace for subscriber attributes. Audience conditions target them as
         * `attr.<key>`, keeping marketer-defined keys from colliding with built-in
         * fields — an attribute called `language` would otherwise shadow one.
         */
        const val ATTRIBUTE_NAMESPACE = "attr."

        private const val SEGMENTS_KEY = "pe_iam_segments"

        /**
         * Field names written by the last [applySubscriberState] call, so the next one
         * can clear them. Avoids hard-coding the scalar field list here, which would
         * duplicate the catalogue in `IAMRulesEngine` and be free to drift from it.
         */
        private const val SCALAR_KEYS_KEY = "pe_iam_subscriber_scalar_keys"
        private const val SUBSCRIBER_STATE_LOADED_KEY = "pe_iam_subscriber_state_loaded"

        /** Subscriber the cached snapshot describes, so a new identity discards it. */
        private const val SUBSCRIBER_IDENTITY_KEY = "pe_iam_subscriber_identity"
        
        @Volatile
        private var instance: IAMDevicePropertiesManager? = null
        
        /**
         * Gets the singleton instance
         * Uses double-checked locking for thread safety
         */
        @JvmStatic
        fun getInstance(context: Context): IAMDevicePropertiesManager {
            // Use local variable for thread safety in double-checked locking
            var localInstance = instance
            if (localInstance == null) {
                synchronized(this) {
                    localInstance = instance
                    if (localInstance == null) {
                        localInstance = IAMDevicePropertiesManager(context)
                        instance = localInstance
                    }
                }
            }
            return localInstance!!
        }
    }
} 