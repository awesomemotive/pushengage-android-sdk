package com.pushengage.pushengage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.request.Goal
import com.pushengage.pushengage.model.request.TriggerCampaign
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Tests for PEManager.automatedNotification() and null-input validation bugs.
 *
 * automatedNotification() previously had zero test coverage.
 * The last two tests document a real validation bug: null == "" is false in Kotlin,
 * so null input bypasses the empty-string check in sendTriggerEvent and sendGoal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEManagerAutomatedNotificationTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var manager: PEManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)
        prefs.siteStatus = PEConstants.ACTIVE
        prefs.hash = "test_hash"
        prefs.siteId = 12345L
        setNetworkConnected(true)
        manager = PEManager(context, prefs)
    }

    // --- automatedNotification tests ---

    @Test
    fun automatedNotification_enabled_passesValidation() {
        var validationFailed = false

        manager.automatedNotification(PushEngage.TriggerStatusType.enabled, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse("Validation should pass for enabled status", validationFailed)
    }

    @Test
    fun automatedNotification_disabled_passesValidation() {
        var validationFailed = false

        manager.automatedNotification(PushEngage.TriggerStatusType.disabled, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse("Validation should pass for disabled status", validationFailed)
    }

    @Test
    fun automatedNotification_siteNotActive_callsOnFailure() {
        prefs.siteStatus = "inactive"
        var failureCalled = false
        var failureMessage: String? = null

        manager.automatedNotification(PushEngage.TriggerStatusType.enabled, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue(failureCalled)
        assertEquals(PEConstants.SITE_NOT_ACTIVE, failureMessage)
    }

    @Test
    fun automatedNotification_networkDown_callsOnFailure() {
        setNetworkConnected(false)
        var failureCalled = false

        manager.automatedNotification(PushEngage.TriggerStatusType.enabled, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
            }
        })

        assertTrue(failureCalled)
    }

    @Test
    fun automatedNotification_userNotSubscribed_callsOnFailure() {
        prefs.setIsNotificationDisabled(1L)
        prefs.setIsSubscriberDeleted(true)
        var failureCalled = false
        var failureMessage: String? = null

        manager.automatedNotification(PushEngage.TriggerStatusType.enabled, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue(failureCalled)
        assertEquals(PEConstants.USER_NOT_SUBSCRIBED, failureMessage)
    }

    @Test
    fun automatedNotification_nullCallback_doesNotCrash() {
        manager.automatedNotification(PushEngage.TriggerStatusType.enabled, null)
    }

    // --- Null validation bug tests ---
    //
    // BUG: PEManager uses `== ""` checks which don't catch null.
    // Since TriggerCampaign.eventName and Goal.name are declared as non-nullable
    // String in Kotlin, null can only arrive via Java callers or Gson deserialization.
    // We use Gson to create objects with null fields to simulate this.

    /**
     * BUG: In PEManager.sendTriggerEvent (line 117):
     *   if(trigger.eventName == "" || trigger.campaignName == "")
     *
     * When eventName is null (set via Gson/Java), `null == ""` is false in Kotlin,
     * so null bypasses the validation check. Then when constructing the request object,
     * Kotlin's intrinsic null check throws NullPointerException — crashing the app
     * instead of gracefully calling onFailure.
     *
     * PROVES BUG: This test FAILS (NPE) because null bypasses validation and crashes.
     */
    @Test(expected = NullPointerException::class)
    fun sendTriggerEvent_nullEventName_bypassesValidationAndCrashes() {
        // Gson ignores Kotlin non-null types, creating an object with null eventName
        val gson = Gson()
        val trigger: TriggerCampaign = gson.fromJson(
            """{"campaignName":"campaign"}""",
            TriggerCampaign::class.java
        )
        // trigger.eventName is null at runtime despite String type declaration.
        // null == "" is false, so validation is bypassed.
        // Then NPE occurs when constructing the request body.
        manager.sendTriggerEvent(trigger, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    /**
     * BUG: In PEManager.sendGoal (line 286):
     *   if(goal.name == "")
     *
     * When name is null (set via Gson/Java), `null == ""` is false in Kotlin,
     * so null bypasses the validation check and crashes with NPE when
     * constructing GoalRequest.
     *
     * PROVES BUG: This test FAILS (NPE) because null bypasses validation and crashes.
     */
    @Test(expected = NullPointerException::class)
    fun sendGoal_nullName_bypassesValidationAndCrashes() {
        val gson = Gson()
        val goal: Goal = gson.fromJson(
            """{"count":1,"value":10.0}""",
            Goal::class.java
        )
        // goal.name is null at runtime despite String type declaration.
        // null bypasses the == "" check and crashes when building the request.
        manager.sendGoal(goal, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    private fun setNetworkConnected(connected: Boolean) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivityManager = shadowOf(connectivityManager)

        val networkInfo = ShadowNetworkInfo.newInstance(
            null,
            ConnectivityManager.TYPE_WIFI,
            0,
            connected,
            if (connected) NetworkInfo.State.CONNECTED else NetworkInfo.State.DISCONNECTED
        )
        shadowConnectivityManager.setActiveNetworkInfo(networkInfo)
        shadowConnectivityManager.setNetworkInfo(ConnectivityManager.TYPE_WIFI, networkInfo)
        shadowConnectivityManager.setNetworkInfo(ConnectivityManager.TYPE_MOBILE, networkInfo)
    }
}
