package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the subscriber-attributes and profile-id family on PushEngage:
 *   getSubscriberDetails, getSubscriberAttributes,
 *   addSubscriberAttributes, setSubscriberAttributes, deleteSubscriberAttributes,
 *   addProfileId
 *
 * Every method in this group routes through PEUtilities.apiPreValidate(context). Under
 * Robolectric with no network state set up, the validator returns NETWORK_ISSUE or
 * SITE_NOT_ACTIVE, so the callbacks fire with `onFailure(400, validationResult)` and we
 * never hit the network — perfect for unit-level coverage of the entry paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageAttributesAndProfileTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")

        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", PEPrefs(context))
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
    }

    private fun assertValidationFailure(cb: PushEngageTestSupport.RecordingCallback) {
        assertTrue("Expected onFailure to fire under no-network/no-site-status conditions", cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertTrue(
            "Failure message should be a validation result, got: ${cb.failureMessage}",
            cb.failureMessage == PEConstants.NETWORK_ISSUE ||
                    cb.failureMessage == PEConstants.SITE_NOT_ACTIVE ||
                    cb.failureMessage == PEConstants.USER_NOT_SUBSCRIBED
        )
    }

    // ---- getSubscriberDetails ----

    @Test
    fun getSubscriberDetails_invalidPreflight_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriberDetails(listOf("country", "city"), cb)
        assertValidationFailure(cb)
    }

    @Test
    fun getSubscriberDetails_nullCallback_invalidPreflight_doesNotCrash() {
        PushEngage.getSubscriberDetails(listOf("country"), null)
    }

    // ---- getSubscriberAttributes ----

    @Test
    fun getSubscriberAttributes_invalidPreflight_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriberAttributes(cb)
        assertValidationFailure(cb)
    }

    @Test
    fun getSubscriberAttributes_nullCallback_doesNotCrash() {
        PushEngage.getSubscriberAttributes(null)
    }

    // ---- addSubscriberAttributes ----

    @Test
    fun addSubscriberAttributes_singleArg_doesNotCrash() {
        val obj = JSONObject().apply { put("country", "IN") }
        PushEngage.addSubscriberAttributes(obj)
    }

    @Test
    fun addSubscriberAttributes_withCallback_invalidPreflight_callsOnFailure() {
        val obj = JSONObject().apply { put("country", "IN"); put("age", 30) }
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.addSubscriberAttributes(obj, cb)
        assertValidationFailure(cb)
    }

    @Test
    fun addSubscriberAttributes_emptyObject_doesNotCrash() {
        PushEngage.addSubscriberAttributes(JSONObject())
    }

    // ---- setSubscriberAttributes ----

    @Test
    fun setSubscriberAttributes_singleArg_doesNotCrash() {
        val obj = JSONObject().apply { put("plan", "pro") }
        PushEngage.setSubscriberAttributes(obj)
    }

    @Test
    fun setSubscriberAttributes_withCallback_invalidPreflight_callsOnFailure() {
        val obj = JSONObject().apply { put("plan", "enterprise") }
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.setSubscriberAttributes(obj, cb)
        assertValidationFailure(cb)
    }

    // ---- deleteSubscriberAttributes ----

    @Test
    fun deleteSubscriberAttributes_singleArg_doesNotCrash() {
        PushEngage.deleteSubscriberAttributes(listOf("age", "city"))
    }

    @Test
    fun deleteSubscriberAttributes_withCallback_invalidPreflight_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.deleteSubscriberAttributes(listOf("age"), cb)
        assertValidationFailure(cb)
    }

    @Test
    fun deleteSubscriberAttributes_emptyList_doesNotCrash() {
        PushEngage.deleteSubscriberAttributes(emptyList<String>())
    }

    // ---- addProfileId ----

    @Test
    fun addProfileId_singleArg_doesNotCrash() {
        PushEngage.addProfileId("user_12345")
    }

    @Test
    fun addProfileId_withCallback_invalidPreflight_callsOnFailure() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.addProfileId("user_12345", cb)
        assertValidationFailure(cb)
    }

    @Test
    fun addProfileId_nullCallback_doesNotCrash() {
        PushEngage.addProfileId("user_xyz", null)
    }
}
