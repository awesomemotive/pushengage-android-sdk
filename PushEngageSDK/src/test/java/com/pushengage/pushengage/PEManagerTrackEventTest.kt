package com.pushengage.pushengage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.request.TrackEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Tests for PEManager.trackEvent — input validation, preflight gates, and request construction.
 * Mirrors the structure of PEManagerAddAlertTest. Network calls fail (no mock server) but the
 * validation branches don't reach the network, so we verify behavior up to that point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEManagerTrackEventTest {

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

    // ---- Input validation ----

    @Test
    fun trackEvent_emptyEventName_callsOnFailureWithMessage() {
        var failureCode: Int? = null
        var failureMessage: String? = null

        manager.trackEvent(TrackEvent(eventName = ""), object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCode = errorCode
                failureMessage = errorMessage
            }
        })

        assertEquals(400, failureCode)
        assertEquals("Event name is required", failureMessage)
    }

    @Test
    fun trackEvent_emptyEventName_nullCallback_doesNotCrash() {
        manager.trackEvent(TrackEvent(eventName = ""), null)
    }

    // ---- data-map type validation ----

    @Test
    fun trackEvent_dataWithUnsupportedType_callsOnFailure() {
        var failureCode: Int? = null
        var failureMessage: String? = null

        val data = mapOf<String, Any>("nested" to listOf("a", "b"))
        manager.trackEvent(
            TrackEvent(eventName = "MySite.AddToCart", data = data),
            object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {}
                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    failureCode = errorCode
                    failureMessage = errorMessage
                }
            }
        )

        assertEquals(400, failureCode)
        assertNotNull(failureMessage)
        assertTrue(
            "Failure message must call out the offending key and type, was: $failureMessage",
            failureMessage!!.contains("nested") && failureMessage!!.contains("unsupported type")
        )
    }

    @Test
    fun trackEvent_dataWithBlankKey_callsOnFailure() {
        var failureMessage: String? = null

        manager.trackEvent(
            TrackEvent(eventName = "MySite.AddToCart", data = mapOf("" to "value")),
            object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {}
                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    failureMessage = errorMessage
                }
            }
        )

        assertEquals("TrackEvent.data keys must be non-blank", failureMessage)
    }

    @Test
    fun trackEvent_dataWithAllowedPrimitives_passesValidation() {
        // String, Int, Long, Double, Boolean, and null values must all be accepted.
        val data: Map<String, Any?> = mapOf(
            "str" to "value",
            "int" to 42,
            "long" to 1234L,
            "double" to 3.14,
            "bool" to true,
            "nullable" to null
        )

        var validationFailureMessage: String? = null
        @Suppress("UNCHECKED_CAST")
        manager.trackEvent(
            TrackEvent(eventName = "MySite.AddToCart", data = data as Map<String, Any>),
            object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {}
                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    if (errorMessage != null &&
                        (errorMessage.startsWith("TrackEvent.data") ||
                            errorMessage == "Event name is required")
                    ) {
                        validationFailureMessage = errorMessage
                    }
                }
            }
        )

        assertNull(
            "Primitive types must not trigger validation failure (got: $validationFailureMessage)",
            validationFailureMessage
        )
    }

    @Test
    fun trackEvent_validEventName_passesValidation() {
        var validationFailed = false
        manager.trackEvent(TrackEvent(eventName = "MySite.AddToCart"), object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                // Network failure is expected here; treat preflight strings as validation failure.
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED ||
                    errorMessage == PEConstants.NETWORK_ISSUE ||
                    errorMessage == "Event name is required"
                ) {
                    validationFailed = true
                }
            }
        })
        assertFalse("Validation must pass for a non-empty event name", validationFailed)
    }

    // ---- Preflight gates ----

    @Test
    fun trackEvent_siteNotActive_callsOnFailureWithSiteNotActive() {
        prefs.siteStatus = "inactive"
        var failureMessage: String? = null
        manager.trackEvent(TrackEvent("evt"), object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureMessage = errorMessage
            }
        })
        assertEquals(PEConstants.SITE_NOT_ACTIVE, failureMessage)
    }

    @Test
    fun trackEvent_networkDown_callsOnFailureWithNetworkIssue() {
        setNetworkConnected(false)
        var failureMessage: String? = null
        manager.trackEvent(TrackEvent("evt"), object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureMessage = errorMessage
            }
        })
        assertEquals(PEConstants.NETWORK_ISSUE, failureMessage)
    }

    @Test
    fun trackEvent_siteNotActive_nullCallback_doesNotCrash() {
        prefs.siteStatus = "inactive"
        manager.trackEvent(TrackEvent("evt"), null)
    }

    // ---- Defaults applied at the manager when payload optionals are null ----

    @Test
    fun trackEvent_optionalFieldsNull_doesNotCrash() {
        // Verifies the manager's `?: "PushEngage"` and `?: emptyMap()` defaults; preflight
        // may still fail because of test environment, but we just want to ensure no NPE.
        manager.trackEvent(
            TrackEvent(eventName = "evt", provider = null, eventType = null, profileId = null, data = null),
            object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {}
                override fun onFailure(errorCode: Int?, errorMessage: String?) {}
            }
        )
    }

    @Test
    fun trackEvent_customDataMap_doesNotCrash() {
        manager.trackEvent(
            TrackEvent(eventName = "evt", data = mapOf("k1" to "v1", "k2" to 42)),
            object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {}
                override fun onFailure(errorCode: Int?, errorMessage: String?) {}
            }
        )
    }

    // ---- helpers ----

    private fun setNetworkConnected(connected: Boolean) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(cm)
        val info = ShadowNetworkInfo.newInstance(
            null,
            ConnectivityManager.TYPE_WIFI,
            0,
            connected,
            if (connected) NetworkInfo.State.CONNECTED else NetworkInfo.State.DISCONNECTED
        )
        shadow.setActiveNetworkInfo(info)
        shadow.setNetworkInfo(ConnectivityManager.TYPE_WIFI, info)
        shadow.setNetworkInfo(ConnectivityManager.TYPE_MOBILE, info)
    }
}
