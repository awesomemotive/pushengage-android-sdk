package com.pushengage.pushengage.iam.action

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers [IAMPermissionHelper.isNotificationPermissionGranted] — the SDK-level
 * permission gate. (The old onRequestPermissionsResult forwarding registry is
 * gone: the IAM permission flow is self-contained via the SDK's permission
 * fragment / invisible helper activity, covered in IAMActionHandlerTest.)
 */
@RunWith(RobolectricTestRunner::class)
class IAMPermissionHelperTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    // ------------------------------------------ isNotificationPermissionGranted

    @Test
    @Config(sdk = [28])
    fun `isNotificationPermissionGranted is always true below API 33`() {
        assertTrue(IAMPermissionHelper.isNotificationPermissionGranted(activity))
    }

    @Test
    @Config(sdk = [33])
    fun `isNotificationPermissionGranted is false on API 33 when not granted`() {
        // Robolectric does not auto-grant POST_NOTIFICATIONS on SDK 33.
        assertFalse(IAMPermissionHelper.isNotificationPermissionGranted(activity))
    }

    @Test
    @Config(sdk = [33])
    fun `isNotificationPermissionGranted is true on API 33 once granted`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions("android.permission.POST_NOTIFICATIONS")

        assertTrue(IAMPermissionHelper.isNotificationPermissionGranted(activity))
    }
}
