package com.pushengage.pushengage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEManagerValidationTest {

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

    // --- sendTriggerEvent validation tests ---

    @Test
    fun sendTriggerEvent_emptyEventName_callsOnFailure() {
        val trigger = TriggerCampaign(campaignName = "campaign", eventName = "")
        var failureCalled = false
        var failureMessage: String? = null

        manager.sendTriggerEvent(trigger, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue("onFailure should be called for empty event name", failureCalled)
        assertEquals("One or more inputs provided are not valid.", failureMessage)
    }

    @Test
    fun sendTriggerEvent_emptyCampaignName_callsOnFailure() {
        val trigger = TriggerCampaign(campaignName = "", eventName = "event")
        var failureCalled = false

        manager.sendTriggerEvent(trigger, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
            }
        })

        assertTrue("onFailure should be called for empty campaign name", failureCalled)
    }

    @Test
    fun sendTriggerEvent_bothEmpty_callsOnFailure() {
        val trigger = TriggerCampaign(campaignName = "", eventName = "")
        var failureCalled = false

        manager.sendTriggerEvent(trigger, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
            }
        })

        assertTrue("onFailure should be called when both names are empty", failureCalled)
    }

    @Test
    fun sendTriggerEvent_validInput_passesValidation() {
        val trigger = TriggerCampaign(campaignName = "camp", eventName = "event")
        var failureCalledForValidation = false

        manager.sendTriggerEvent(trigger, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == "One or more inputs provided are not valid.") {
                    failureCalledForValidation = true
                }
            }
        })

        assertFalse("Validation failure should not be called for valid input", failureCalledForValidation)
    }

    // --- sendGoal validation tests ---

    @Test
    fun sendGoal_emptyName_callsOnFailure() {
        val goal = Goal(name = "", count = 1, value = 10.0)
        var failureCalled = false
        var failureMessage: String? = null

        manager.sendGoal(goal, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue("onFailure should be called for empty goal name", failureCalled)
        assertEquals("One or more inputs provided are not valid.", failureMessage)
    }

    @Test
    fun sendGoal_validName_passesValidation() {
        val goal = Goal(name = "purchase", count = 1, value = 10.0)
        var failureCalledForValidation = false

        manager.sendGoal(goal, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == "One or more inputs provided are not valid.") {
                    failureCalledForValidation = true
                }
            }
        })

        assertFalse("Validation failure should not be called for valid goal name", failureCalledForValidation)
    }

    // --- sendTriggerEvent with site not active ---

    @Test
    fun sendTriggerEvent_siteNotActive_callsOnFailure() {
        prefs.siteStatus = "inactive"
        val trigger = TriggerCampaign(campaignName = "camp", eventName = "event")
        var failureCalled = false
        var failureMessage: String? = null

        manager.sendTriggerEvent(trigger, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue(failureCalled)
        assertEquals(PEConstants.SITE_NOT_ACTIVE, failureMessage)
    }

    // --- sendGoal with user not subscribed ---

    @Test
    fun sendGoal_userNotSubscribed_callsOnFailure() {
        prefs.setIsNotificationDisabled(1L)
        prefs.setIsSubscriberDeleted(true)
        val goal = Goal(name = "purchase", count = 1, value = 10.0)
        var failureCalled = false
        var failureMessage: String? = null

        manager.sendGoal(goal, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue(failureCalled)
        assertEquals(PEConstants.USER_NOT_SUBSCRIBED, failureMessage)
    }

    // --- Null callback doesn't crash ---

    @Test
    fun sendTriggerEvent_nullCallback_doesNotCrash() {
        val trigger = TriggerCampaign(campaignName = "", eventName = "")
        manager.sendTriggerEvent(trigger, null)
    }

    @Test
    fun sendGoal_nullCallback_doesNotCrash() {
        val goal = Goal(name = "", count = null, value = null)
        manager.sendGoal(goal, null)
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
