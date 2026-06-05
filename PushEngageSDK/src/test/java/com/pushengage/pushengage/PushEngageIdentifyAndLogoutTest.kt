package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the identify(...) and logout(...) family on PushEngage, mirroring the
 * structure of [PushEngageAttributesAndProfileTest]. Under Robolectric with no
 * network state, PEUtilities.apiPreValidate(context) returns NETWORK_ISSUE or
 * SITE_NOT_ACTIVE, so non-validation paths fall through to that branch and
 * fire onFailure(400, validationResult) without touching the network.
 *
 * Synchronous validation failures (invalid keys, invalid value types, null
 * payload, etc.) short-circuit *before* the preflight, producing a stable
 * onFailure(400, <validator message>) regardless of network state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageIdentifyAndLogoutTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
        prefs = PEPrefs(context)
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
    }

    private fun assertPreflightFailure(cb: PushEngageTestSupport.RecordingCallback) {
        assertTrue("Expected onFailure for failed preflight", cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertTrue(
            "Failure message should be a validation result, got: ${cb.failureMessage}",
            cb.failureMessage == PEConstants.NETWORK_ISSUE ||
                cb.failureMessage == PEConstants.SITE_NOT_ACTIVE ||
                cb.failureMessage == PEConstants.USER_NOT_SUBSCRIBED,
        )
    }

    // ---- identify: synchronous validation ----

    @Test
    fun identify_nullPayload_callsOnFailureWithRequiredMessage() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.identify(null, cb)
        assertTrue(cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertNotNull(cb.failureMessage)
        assertTrue(cb.failureMessage!!.contains("required", ignoreCase = true))
    }

    @Test
    fun identify_emptyPayload_callsOnFailureWithAtLeastOneKey() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.identify(JSONObject(), cb)
        assertTrue(cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertTrue(cb.failureMessage!!.contains("at least one key", ignoreCase = true))
    }

    @Test
    fun identify_invalidKey_callsOnFailureWithKeyNotValid() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.identify(JSONObject().put("favorite_color", "blue"), cb)
        assertTrue(cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertTrue(cb.failureMessage!!.contains("favorite_color"))
    }

    @Test
    fun identify_nullCallback_doesNotCrash() {
        PushEngage.identify(JSONObject().put("email", "a@b.com"))
    }

    @Test
    fun identify_validPayload_invalidPreflight_callsOnFailureWithPreflightMessage() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.identify(JSONObject().put("email", "a@b.com"), cb)
        assertPreflightFailure(cb)
    }

    // (Cache short-circuit success behavior is exercised in PushEngageNetworkPathsTest,
    // where MockWebServer + an active-site/connected-network setup makes preflight
    // pass so the cache check actually runs.)

    // ---- logout: synchronous validation ----

    @Test
    fun logout_invalidName_callsOnFailureWithNotValid() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.logout(listOf("first_name", "ssn"), cb)
        assertTrue(cb.failureInvoked)
        assertEquals(400, cb.failureCode)
        assertTrue(cb.failureMessage!!.contains("ssn"))
    }

    @Test
    fun logout_nullList_invalidPreflight_callsOnFailureWithPreflightMessage() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.logout(null as List<String>?, cb)
        assertPreflightFailure(cb)
    }

    @Test
    fun logout_emptyList_invalidPreflight_callsOnFailureWithPreflightMessage() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.logout(emptyList(), cb)
        assertPreflightFailure(cb)
    }

    @Test
    fun logout_validList_invalidPreflight_callsOnFailureWithPreflightMessage() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.logout(listOf("email", "first_name"), cb)
        assertPreflightFailure(cb)
    }

    @Test
    fun logout_nullCallback_doesNotCrash() {
        PushEngage.logout(listOf("email"))
        PushEngage.logout(null as List<String>?)
    }

    // ---- cache wipe on unsubscribe / hash change paths (verified via PEPrefs.clearSiteSpecificData) ----

    @Test
    fun clearSiteSpecificData_wipesSubscriberFieldsCache() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com"))
        assertFalse(prefs.subscriberFields.isEmpty())
        prefs.clearSiteSpecificData()
        assertTrue(prefs.subscriberFields.isEmpty())
    }

    // ---- addProfileId writes to the cache when its handler runs successfully ----
    //
    // The handler-level write is exercised by PESubscriberFieldsHandlerTest;
    // here we assert PushEngage.addProfileId's *no-op* failure path leaves
    // the cache untouched, so an invalid preflight doesn't poison it.

    @Test
    fun addProfileId_invalidPreflight_leavesCacheUnchanged() {
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.addProfileId("u_99", cb)
        assertTrue(cb.failureInvoked)
        assertNull(prefs.subscriberFields["profile_id"])
    }
}
