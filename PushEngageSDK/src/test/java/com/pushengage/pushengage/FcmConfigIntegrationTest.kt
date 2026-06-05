package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallationsException
import com.pushengage.pushengage.Callbacks.FcmConfigErrorListener
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wiring tests for FCM-config validation. The pure comparison logic is covered in
 * FcmConfigValidatorTest; here we verify that:
 *   - The sync callback persists the new `firebase_project_id` field to PEPrefs.
 *   - runConfigValidation fires the registered listener with the correct error code
 *     for each mismatch flavor, and reports the right short-circuit decision.
 *   - handleFirebaseTokenFailure classifies FIS `BAD_CONFIG` as
 *     FCM_LOCAL_CONFIG_INVALID and leaves all other failures alone.
 *
 * The validator's runtime lookup of `FirebaseApp.getInstance()` is mocked via
 * Mockito's `mockStatic`. That mock is thread-scoped — so we drive
 * runConfigValidation synchronously on the test thread rather than going through
 * the async OkHttp callback for mismatch assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FcmConfigIntegrationTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var prefs: PEPrefs
    private lateinit var firebaseAppStatic: MockedStatic<FirebaseApp>
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null
    private var originalListener: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        server = MockWebServer()
        server.start()

        prefs = PEPrefs(context).apply {
            siteKey = "test_site_key"
            siteStatus = PEConstants.ACTIVE
            backendCdnUrl = server.url("/").toString()
        }

        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
        originalListener = PushEngageTestSupport.getStaticField("fcmConfigErrorListener")
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)
        PushEngageTestSupport.setStaticField("fcmConfigErrorListener", null)

        firebaseAppStatic = Mockito.mockStatic(FirebaseApp::class.java)
    }

    @After
    fun tearDown() {
        firebaseAppStatic.close()
        server.shutdown()
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
        PushEngageTestSupport.setStaticField("fcmConfigErrorListener", originalListener)
    }

    private fun stubFirebaseOptions(senderId: String?, projectId: String?) {
        val mockApp = mock<FirebaseApp>()
        val mockOptions = mock<FirebaseOptions>()
        whenever(mockOptions.gcmSenderId).thenReturn(senderId)
        whenever(mockOptions.projectId).thenReturn(projectId)
        whenever(mockApp.options).thenReturn(mockOptions)
        firebaseAppStatic.`when`<FirebaseApp> { FirebaseApp.getInstance() }.thenReturn(mockApp)
    }

    private fun invokeRunConfigValidation(serverSenderId: String?, serverProjectId: String?): Boolean {
        val method = PushEngage::class.java.getDeclaredMethod(
            "runConfigValidation",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, serverSenderId, serverProjectId) as Boolean
    }

    private fun invokeHandleFirebaseTokenFailure(ex: Throwable?) {
        val method = PushEngage::class.java.getDeclaredMethod(
            "handleFirebaseTokenFailure",
            Throwable::class.java
        )
        method.isAccessible = true
        method.invoke(null, ex)
    }

    private fun syncResponseBody(senderId: String, projectId: String?): String {
        val projectField = if (projectId == null) "" else ""","firebase_project_id":"$projectId""""
        return """
            {
              "data": {
                "site_id": 1,
                "site_status": "active",
                "is_eu": 0,
                "firebase_sender_id": "$senderId"$projectField,
                "geo_fetch": true,
                "delete_on_notification_disable": false,
                "api": {
                  "backend": "${server.url("/")}",
                  "backend_cdn": "${server.url("/")}",
                  "analytics": "${server.url("/")}",
                  "trigger": "${server.url("/")}",
                  "optin": "${server.url("/")}",
                  "log": "${server.url("/")}"
                }
              }
            }
        """.trimIndent()
    }

    // ---- sync callback persists the new firebase_project_id field ----

    /**
     * Waits up to [timeoutMs] for [predicate] to become true, polling every 20ms.
     * Avoids the flaky-on-CI pattern of a fixed Thread.sleep waiting for an async write.
     */
    private fun awaitUntil(timeoutMs: Long = 5000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    @Test
    fun sync_response_persistsFirebaseProjectIdToPrefs_andDoesNotFireListenerOnFirebaseUnavailable() {
        // FirebaseApp.getInstance() returns null on the OkHttp dispatcher thread (the
        // mockStatic stub is thread-scoped), so the validator returns FirebaseUnavailable
        // and the sync proceeds without firing the listener — the path tested here.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseBody(senderId = "111", projectId = "my-project")
            )
        )
        var listenerFired = false
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { _, _ -> listenerFired = true })

        val method = PushEngage::class.java.getDeclaredMethod("callAndroidSync")
        method.isAccessible = true
        method.invoke(null)

        val syncRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Sync request must reach the server", syncRequest)
        assertTrue(syncRequest!!.path!!.contains("/sync/android"))

        assertTrue("Sync callback must finish writing firebaseProjectId to prefs",
                awaitUntil { prefs.firebaseProjectId == "my-project" })

        assertEquals("Sender ID must be persisted to prefs", "111", prefs.projectId)
        assertEquals("New firebase_project_id must be persisted to prefs",
                "my-project", prefs.firebaseProjectId)
        assertEquals("FirebaseUnavailable path must not fire the listener",
                false, listenerFired)
    }

    @Test
    fun sync_response_withoutFirebaseProjectId_persistsEmptyStringForLegacyBackend() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseBody(senderId = "111", projectId = null)
            )
        )

        val method = PushEngage::class.java.getDeclaredMethod("callAndroidSync")
        method.isAccessible = true
        method.invoke(null)

        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue("Sync callback must finish writing sender_id to prefs",
                awaitUntil { prefs.projectId == "111" })

        assertEquals("111", prefs.projectId)
        // Backend hasn't rolled out the field: prefs setter receives null,
        // which SharedPreferences stores as "" when read back.
        assertEquals("Legacy backend produces empty firebase_project_id in prefs",
                "", prefs.firebaseProjectId)
    }

    // ---- runConfigValidation: result interpretation + listener wiring ----

    @Test
    fun runConfigValidation_match_returnsFalse_andDoesNotFireListener() {
        stubFirebaseOptions(senderId = "111", projectId = "my-project")
        var captured: Int? = null
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { code, _ -> captured = code })

        val isMismatch = invokeRunConfigValidation("111", "my-project")

        assertEquals(false, isMismatch)
        assertNull("Listener must not fire on match", captured)
    }

    @Test
    fun runConfigValidation_senderMismatch_returnsTrue_andFiresListenerWithSenderCode() {
        stubFirebaseOptions(senderId = "111", projectId = "my-project")
        var capturedCode: Int? = null
        var capturedMessage: String? = null
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { code, message ->
            capturedCode = code
            capturedMessage = message
        })

        val isMismatch = invokeRunConfigValidation("999", "my-project")

        assertEquals(true, isMismatch)
        assertEquals(PEErrorCodes.FCM_SENDER_ID_MISMATCH, capturedCode)
        assertTrue("Message should mention sender ID",
                capturedMessage!!.contains("sender", ignoreCase = true))
    }

    @Test
    fun runConfigValidation_projectMismatch_returnsTrue_andFiresListenerWithProjectCode() {
        stubFirebaseOptions(senderId = "111", projectId = "my-project")
        var capturedCode: Int? = null
        var capturedMessage: String? = null
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { code, message ->
            capturedCode = code
            capturedMessage = message
        })

        val isMismatch = invokeRunConfigValidation("111", "wrong-project")

        assertEquals(true, isMismatch)
        assertEquals(PEErrorCodes.FCM_PROJECT_ID_MISMATCH, capturedCode)
        assertTrue("Message should mention project ID",
                capturedMessage!!.contains("project", ignoreCase = true))
    }

    @Test
    fun runConfigValidation_bothMismatch_returnsTrue_andFiresListenerWithBothMismatchCode() {
        stubFirebaseOptions(senderId = "111", projectId = "my-project")
        var capturedCode: Int? = null
        var capturedMessage: String? = null
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { code, message ->
            capturedCode = code
            capturedMessage = message
        })

        val isMismatch = invokeRunConfigValidation("999", "wrong-project")

        assertEquals(true, isMismatch)
        // Distinct code so host apps can react differently to "both differ" vs
        // a single-field mismatch (e.g., escalate severity in a debug banner).
        assertEquals(PEErrorCodes.FCM_CONFIG_BOTH_MISMATCH, capturedCode)
        assertTrue("Message should mention sender_id",
                capturedMessage!!.contains("sender_id", ignoreCase = true))
        assertTrue("Message should mention project_id",
                capturedMessage!!.contains("project_id", ignoreCase = true))
    }

    @Test
    fun runConfigValidation_firebaseUnavailable_returnsFalse_andDoesNotFireListener() {
        // No FirebaseApp.getInstance() stub → returns null → FirebaseUnavailable result
        firebaseAppStatic.`when`<FirebaseApp> { FirebaseApp.getInstance() }.thenReturn(null)
        var fired = false
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { _, _ -> fired = true })

        val isMismatch = invokeRunConfigValidation("111", "my-project")

        assertEquals("FirebaseUnavailable must not short-circuit", false, isMismatch)
        assertEquals("FirebaseUnavailable must not fire the listener", false, fired)
    }

    @Test
    fun runConfigValidation_mismatchWithoutListener_returnsTrue_andDoesNotCrash() {
        stubFirebaseOptions(senderId = "111", projectId = "my-project")
        // No listener registered — must still report the mismatch via the return value.
        val isMismatch = invokeRunConfigValidation("999", "my-project")
        assertEquals(true, isMismatch)
    }

    // ---- FIS BAD_CONFIG token-fetch interpretation ----

    @Test
    fun handleFirebaseTokenFailure_badConfig_firesLocalConfigInvalidCode() {
        var capturedCode: Int? = null
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { code, _ ->
            capturedCode = code
        })

        invokeHandleFirebaseTokenFailure(
            FirebaseInstallationsException(
                "Firebase Installations rejected install",
                FirebaseInstallationsException.Status.BAD_CONFIG
            )
        )

        assertEquals(PEErrorCodes.FCM_LOCAL_CONFIG_INVALID, capturedCode)
    }

    @Test
    fun handleFirebaseTokenFailure_fisUnavailable_doesNotFireListener() {
        var fired = false
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { _, _ -> fired = true })

        invokeHandleFirebaseTokenFailure(
            FirebaseInstallationsException(
                "FIS unavailable",
                FirebaseInstallationsException.Status.UNAVAILABLE
            )
        )

        assertEquals("Transient FIS errors must not be classified as config invalid",
                false, fired)
    }

    @Test
    fun handleFirebaseTokenFailure_genericIoException_doesNotFireListener() {
        var fired = false
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { _, _ -> fired = true })

        invokeHandleFirebaseTokenFailure(IOException("network unreachable"))

        assertEquals("Network errors must not be classified as config invalid", false, fired)
    }

    @Test
    fun handleFirebaseTokenFailure_nullException_doesNotCrash() {
        invokeHandleFirebaseTokenFailure(null)
    }
}
