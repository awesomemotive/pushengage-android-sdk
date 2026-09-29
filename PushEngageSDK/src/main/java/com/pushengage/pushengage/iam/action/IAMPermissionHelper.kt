package com.pushengage.pushengage.iam.action

import android.app.Activity
import android.content.pm.PackageManager
import com.pushengage.pushengage.helper.PELogger

/**
 * Notification-permission utilities for in-app messaging.
 *
 * The IAM permission flow is fully self-contained: the
 * `request_notification_permission` action runs through the SDK's own
 * permission fragment / invisible helper activity, which receives the system
 * result itself and subscribes on grant. Host apps do NOT need to forward
 * onRequestPermissionsResult to the SDK.
 */
internal object IAMPermissionHelper {

    /**
     * Checks if notification permission is granted
     * 
     * @param activity The activity to check permission for
     * @return true if notification permission is granted, false otherwise
     */
    @JvmStatic
    fun isNotificationPermissionGranted(activity: Activity): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                activity, 
                "android.permission.POST_NOTIFICATIONS"
            ) == PackageManager.PERMISSION_GRANTED
            PELogger.debug("IAMPermissionHelper: Notification permission is ${if (granted) "granted" else "not granted"}")
            return granted
        }
        PELogger.debug("IAMPermissionHelper: Device running Android < 13, permission not required")
        return true // For older Android versions, permission is granted at install time
    }
} 