package com.pushengage.pushengage.permissionhandling

import android.content.pm.PackageManager
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [PEPermissionHelperActivity] lifecycle and the static pending-callback wiring.
 *
 * The activity has two behavior paths based on SDK level:
 *  - SDK < 33 (legacy): onCreate immediately calls handleResult(true) and finishes.
 *  - SDK >= 33: onCreate checks ContextCompat.checkSelfPermission and either
 *    short-circuits (already granted) or requests permission via ActivityCompat.
 *
 * Robolectric reports `PackageManager.PERMISSION_GRANTED` by default in unit tests, so the
 * SDK 33 path exercises the "already granted" branch. The denied/runtime-request branch is
 * harder to drive deterministically here and is left for instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
class PEPermissionHelperActivityTest {

    private lateinit var firebaseAppStatic: MockedStatic<FirebaseApp>
    private lateinit var firebaseMessagingStatic: MockedStatic<FirebaseMessaging>

    @Before
    fun setUp() {
        // The activity's handleResult(true) path calls PushEngage.subscribe(), which would
        // otherwise blow up trying to initialize FirebaseApp in the test JVM. Stub both
        // entry points so the call is a no-op.
        firebaseAppStatic = Mockito.mockStatic(FirebaseApp::class.java)
        firebaseMessagingStatic = Mockito.mockStatic(FirebaseMessaging::class.java)

        val firebaseApp = Mockito.mock(FirebaseApp::class.java)
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        @Suppress("UNCHECKED_CAST")
        val tokenTask = Mockito.mock(Task::class.java) as Task<String>

        firebaseAppStatic.`when`<FirebaseApp> { FirebaseApp.initializeApp(any()) }.thenReturn(firebaseApp)
        firebaseMessagingStatic.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }
            .thenReturn(firebaseMessaging)
        Mockito.doNothing().`when`(firebaseMessaging).setAutoInitEnabled(true)
        whenever(firebaseMessaging.getToken()).thenReturn(tokenTask)
        whenever(tokenTask.addOnCompleteListener(any())).thenReturn(tokenTask)
        whenever(tokenTask.addOnFailureListener(any())).thenReturn(tokenTask)
    }

    @After
    fun tearDown() {
        // Reset the static callback so tests don't leak state.
        setPendingCallback(null)
        firebaseMessagingStatic.close()
        firebaseAppStatic.close()
    }

    // ---- Pre-Android-13 path (SDK 28) ----

    @Test
    @Config(sdk = [28])
    fun onCreate_preAndroid13_invokesCallbackWithGrantedTrue_andFinishes() {
        val recorded = RecordingPermissionCallback()
        setPendingCallback(recorded)

        val activity = Robolectric.buildActivity(PEPermissionHelperActivity::class.java)
            .create()
            .get()

        assertTrue("Callback should fire under SDK < 33", recorded.invoked)
        assertTrue("Permission should be reported as granted on pre-13", recorded.granted)
        assertNull(recorded.error)
        assertTrue("Activity should self-finish", activity.isFinishing)
    }

    @Test
    @Config(sdk = [28])
    fun onCreate_preAndroid13_clearsPendingCallback() {
        setPendingCallback(RecordingPermissionCallback())
        assertNotNull(getPendingCallback())

        Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()

        assertNull("pendingCallback must be nulled after firing", getPendingCallback())
    }

    @Test
    @Config(sdk = [28])
    fun onCreate_preAndroid13_withNullCallback_doesNotCrash() {
        setPendingCallback(null)
        val activity = Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
    }

    // ---- Android-13+ path (SDK 33) ----

    @Test
    @Config(sdk = [33])
    fun onCreate_android13_permissionNotYetGranted_doesNotFireCallbackImmediately() {
        // Robolectric does NOT auto-grant POST_NOTIFICATIONS at SDK 33+; the activity
        // therefore takes the requestPermissions() path and the callback fires later via
        // onRequestPermissionsResult (covered by the dedicated test below).
        val recorded = RecordingPermissionCallback()
        setPendingCallback(recorded)

        Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()

        assertFalse("Callback should not fire until permission result arrives", recorded.invoked)
        assertNotNull("pendingCallback stays set until onRequestPermissionsResult", getPendingCallback())
    }

    // ---- onRequestPermissionsResult ----

    @Test
    @Config(sdk = [33])
    fun onRequestPermissionsResult_grantedResult_invokesCallback() {
        val recorded = RecordingPermissionCallback()
        setPendingCallback(recorded)

        val activity = Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()
        // The "already granted" path will have fired the callback once. Reset so we can re-test
        // the explicit result path.
        recorded.invoked = false
        setPendingCallback(recorded)

        activity.onRequestPermissionsResult(
            /* requestCode */ 100,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )

        assertTrue(recorded.invoked)
        assertTrue(recorded.granted)
        assertNull(recorded.error)
    }

    @Test
    @Config(sdk = [33])
    fun onRequestPermissionsResult_deniedResult_invokesCallbackWithError() {
        val recorded = RecordingPermissionCallback()
        setPendingCallback(recorded)

        val activity = Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()
        recorded.invoked = false
        setPendingCallback(recorded)

        activity.onRequestPermissionsResult(
            100,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )

        assertTrue(recorded.invoked)
        assertFalse(recorded.granted)
        assertNotNull(recorded.error)
        assertEquals("Permission denied", recorded.error?.message)
    }

    @Test
    @Config(sdk = [33])
    fun onRequestPermissionsResult_unknownRequestCode_isIgnored() {
        val recorded = RecordingPermissionCallback()
        setPendingCallback(recorded)
        val activity = Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()
        recorded.invoked = false

        activity.onRequestPermissionsResult(
            /* unknown */ 9999,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )

        assertFalse("Unknown requestCode must not trigger callback", recorded.invoked)
    }

    @Test
    @Config(sdk = [33])
    fun onRequestPermissionsResult_emptyGrantResults_treatedAsDenied() {
        val recorded = RecordingPermissionCallback()
        setPendingCallback(recorded)
        val activity = Robolectric.buildActivity(PEPermissionHelperActivity::class.java).create().get()
        recorded.invoked = false
        setPendingCallback(recorded)

        activity.onRequestPermissionsResult(
            100,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf() // empty
        )

        assertTrue(recorded.invoked)
        assertFalse(recorded.granted)
        assertNotNull(recorded.error)
    }

    // ---- helpers ----

    private fun getPendingCallback(): Any? {
        val field = PEPermissionHelperActivity::class.java.getDeclaredField("pendingCallback")
        field.isAccessible = true
        return field.get(null)
    }

    private fun setPendingCallback(value: PushEngagePermissionCallback?) {
        val field = PEPermissionHelperActivity::class.java.getDeclaredField("pendingCallback")
        field.isAccessible = true
        field.set(null, value)
    }

    private class RecordingPermissionCallback : PushEngagePermissionCallback {
        var invoked: Boolean = false
        var granted: Boolean = false
        var error: Error? = null

        override fun onPermissionResult(granted: Boolean, error: Error?) {
            invoked = true
            this.granted = granted
            this.error = error
        }
    }
}
