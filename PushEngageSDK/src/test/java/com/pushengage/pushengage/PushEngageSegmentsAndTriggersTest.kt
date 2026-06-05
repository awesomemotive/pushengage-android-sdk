package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest
import com.pushengage.pushengage.model.request.Goal
import com.pushengage.pushengage.model.request.TrackEvent
import com.pushengage.pushengage.model.request.TriggerAlert
import com.pushengage.pushengage.model.request.TriggerCampaign
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the segment / trigger / goal / alert / automated-notification family on PushEngage.
 *
 * - addSegment / removeSegment / addDynamicSegment route through PEUtilities.apiPreValidate.
 * - sendTriggerEvent / sendGoal / addAlert / automatedNotification delegate to PEManager,
 *   which has its own SDK-init and network guards.
 *
 * Under Robolectric defaults (no live network, no active site status), preflight validation
 * fails and callbacks fire with onFailure; PEManager-delegating methods are exercised for
 * smoke-coverage (they have their own dedicated PEManager*Test files for deeper behavior).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageSegmentsAndTriggersTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null
    private var originalManager: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
        originalManager = PushEngageTestSupport.getStaticField("peManager")

        val prefs = PEPrefs(context).apply { siteKey = "seg_test" }
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)
        // Initialize peManager directly (avoid Builder.build() which calls subscribe() →
        // FirebaseApp.getInstance(), which is not configured in the test JVM).
        PushEngageTestSupport.setStaticField("peManager", PEManager(context, prefs))
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
        PushEngageTestSupport.setStaticField("peManager", originalManager)
    }

    private fun assertValidationFailure(cb: PushEngageTestSupport.RecordingCallback) {
        assertTrue("Expected onFailure under invalid preflight", cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertTrue(
            "Failure message should be a validation result, got: ${cb.failureMessage}",
            cb.failureMessage == PEConstants.NETWORK_ISSUE ||
                    cb.failureMessage == PEConstants.SITE_NOT_ACTIVE ||
                    cb.failureMessage == PEConstants.USER_NOT_SUBSCRIBED
        )
    }

    // ---- addSegment ----

    @Test
    fun addSegment_singleArg_doesNotCrash() {
        PushEngage.addSegment(listOf("seg_a", "seg_b"))
    }

    @Test
    fun addSegment_withCallback_invalidPreflight_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.addSegment(listOf("seg_a"), cb)
        assertValidationFailure(cb)
    }

    @Test
    fun addSegment_emptyList_doesNotCrash() {
        PushEngage.addSegment(emptyList())
    }

    // ---- removeSegment ----

    @Test
    fun removeSegment_singleArg_doesNotCrash() {
        PushEngage.removeSegment(listOf("seg_x"))
    }

    @Test
    fun removeSegment_withCallback_invalidPreflight_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.removeSegment(listOf("seg_x"), cb)
        assertValidationFailure(cb)
    }

    // ---- addDynamicSegment ----

    @Test
    fun addDynamicSegment_singleArg_doesNotCrash() {
        val outer = AddDynamicSegmentRequest()
        val segs = listOf(outer.Segment("seg_dyn", 30L))
        PushEngage.addDynamicSegment(segs)
    }

    @Test
    fun addDynamicSegment_withCallback_invalidPreflight_callsOnFailure() {
        val outer = AddDynamicSegmentRequest()
        val segs = listOf(outer.Segment("seg_dyn", 30L))
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.addDynamicSegment(segs, cb)
        assertValidationFailure(cb)
    }

    @Test
    fun addDynamicSegment_emptyList_doesNotCrash() {
        PushEngage.addDynamicSegment(emptyList())
    }

    // ---- automatedNotification ----

    @Test
    fun automatedNotification_singleArg_doesNotCrash() {
        PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled)
        PushEngage.automatedNotification(PushEngage.TriggerStatusType.disabled)
    }

    @Test
    fun automatedNotification_withCallback_doesNotCrash() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled, cb)
        // PEManager may fire either callback path; just assert exactly-one if any.
        assertTrue(
            "At most one callback can fire",
            !(cb.successInvoked && cb.failureInvoked)
        )
    }

    // ---- sendTriggerEvent ----

    @Test
    fun sendTriggerEvent_singleArg_doesNotCrash() {
        val trigger = TriggerCampaign("camp", "evt")
        PushEngage.sendTriggerEvent(trigger)
    }

    @Test
    fun sendTriggerEvent_withCallback_doesNotCrash() {
        val trigger = TriggerCampaign("camp", "evt")
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.sendTriggerEvent(trigger, cb)
        assertTrue(!(cb.successInvoked && cb.failureInvoked))
    }

    // ---- sendGoal ----

    @Test
    fun sendGoal_singleArg_doesNotCrash() {
        val goal = Goal("revenue", 1, 10.0)
        PushEngage.sendGoal(goal)
    }

    @Test
    fun sendGoal_withCallback_doesNotCrash() {
        val goal = Goal("revenue", 1, 10.0)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.sendGoal(goal, cb)
        assertTrue(!(cb.successInvoked && cb.failureInvoked))
    }

    // ---- addAlert ----

    @Test
    fun addAlert_singleArg_doesNotCrash() {
        val alert = TriggerAlert(PushEngage.TriggerAlertType.inventory, "prod_1", "https://example.com", 20.0)
        PushEngage.addAlert(alert)
    }

    @Test
    fun addAlert_withCallback_doesNotCrash() {
        val alert = TriggerAlert(PushEngage.TriggerAlertType.priceDrop, "prod_2", "https://example.com", 25.0)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.addAlert(alert, cb)
        assertTrue(!(cb.successInvoked && cb.failureInvoked))
    }

    // ---- trackEvent ----

    @Test
    fun trackEvent_singleArg_doesNotCrash() {
        PushEngage.trackEvent(TrackEvent("MySite.AddToCart"))
    }

    @Test
    fun trackEvent_withCallback_emptyEventName_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.trackEvent(TrackEvent(""), cb)
        assertTrue("Empty event name must short-circuit to onFailure", cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertEquals("Event name is required", cb.failureMessage)
    }

    @Test
    fun trackEvent_withCallback_doesNotInvokeBoth() {
        val event = TrackEvent("evt", data = mapOf("k" to "v"))
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.trackEvent(event, cb)
        assertTrue("At most one callback path can fire", !(cb.successInvoked && cb.failureInvoked))
    }
}
