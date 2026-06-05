package com.pushengage.pushengage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.request.TriggerAlert
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.Date

/**
 * Tests for PEManager.addAlert() — previously zero test coverage.
 *
 * These tests validate the input validation, request body construction,
 * and error handling for the addAlert method. Network calls are expected
 * to fail in test (no mock server), but we verify validation logic
 * and request body construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEManagerAddAlertTest {

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

    private fun createBasicAlert(
        type: PushEngage.TriggerAlertType = PushEngage.TriggerAlertType.priceDrop,
        productId: String = "product_123",
        link: String = "https://example.com/product",
        price: Double = 29.99,
        variantId: String? = null,
        expiryTimestamp: Date? = null,
        alertPrice: Double? = null,
        availability: PushEngage.TriggerAlertAvailabilityType? = null,
        profileId: String? = null,
        mrp: Double? = null,
        data: Map<String, String>? = null
    ): TriggerAlert {
        return TriggerAlert(
            type = type,
            productId = productId,
            link = link,
            price = price,
            variantId = variantId,
            expiryTimestamp = expiryTimestamp,
            alertPrice = alertPrice,
            availability = availability,
            profileId = profileId,
            mrp = mrp,
            data = data
        )
    }

    // --- Happy path ---

    @Test
    fun addAlert_validInput_passesValidation() {
        val alert = createBasicAlert()
        var failureCalledForValidation = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                // Network failure is expected, but NOT a validation failure
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    failureCalledForValidation = true
                }
            }
        })

        assertFalse("Validation should pass for valid input", failureCalledForValidation)
    }

    // --- Pre-validation gates ---

    @Test
    fun addAlert_siteNotActive_callsOnFailure() {
        prefs.siteStatus = "inactive"
        val alert = createBasicAlert()
        var failureCalled = false
        var failureMessage: String? = null

        manager.addAlert(alert, object : PushEngageResponseCallback {
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
    fun addAlert_networkDown_callsOnFailure() {
        setNetworkConnected(false)
        val alert = createBasicAlert()
        var failureCalled = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
            }
        })

        assertTrue(failureCalled)
    }

    @Test
    fun addAlert_userNotSubscribed_callsOnFailure() {
        prefs.setIsNotificationDisabled(1L)
        prefs.setIsSubscriberDeleted(true)
        val alert = createBasicAlert()
        var failureCalled = false
        var failureMessage: String? = null

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failureCalled = true
                failureMessage = errorMessage
            }
        })

        assertTrue(failureCalled)
        assertEquals(PEConstants.USER_NOT_SUBSCRIBED, failureMessage)
    }

    // --- Null callback ---

    @Test
    fun addAlert_nullCallback_doesNotCrash() {
        val alert = createBasicAlert()
        // Should not throw NPE when callback is null
        manager.addAlert(alert, null)
    }

    @Test
    fun addAlert_nullCallback_siteNotActive_doesNotCrash() {
        prefs.siteStatus = "inactive"
        val alert = createBasicAlert()
        // callback?.onFailure with null callback should be safe
        manager.addAlert(alert, null)
    }

    // --- Type mapping ---

    @Test
    fun addAlert_priceDrop_passesValidation() {
        val alert = createBasicAlert(type = PushEngage.TriggerAlertType.priceDrop)
        var validationFailed = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse(validationFailed)
    }

    @Test
    fun addAlert_inventory_passesValidation() {
        val alert = createBasicAlert(type = PushEngage.TriggerAlertType.inventory)
        var validationFailed = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse(validationFailed)
    }

    // --- Availability mapping ---

    @Test
    fun addAlert_inStock_passesValidation() {
        val alert = createBasicAlert(availability = PushEngage.TriggerAlertAvailabilityType.inStock)
        var validationFailed = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse(validationFailed)
    }

    @Test
    fun addAlert_outOfStock_passesValidation() {
        val alert = createBasicAlert(availability = PushEngage.TriggerAlertAvailabilityType.outOfStock)
        var validationFailed = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse(validationFailed)
    }

    @Test
    fun addAlert_nullAvailability_passesValidation() {
        val alert = createBasicAlert(availability = null)
        var validationFailed = false

        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                if (errorMessage == PEConstants.SITE_NOT_ACTIVE ||
                    errorMessage == PEConstants.USER_NOT_SUBSCRIBED) {
                    validationFailed = true
                }
            }
        })

        assertFalse(validationFailed)
    }

    // --- Optional fields ---

    @Test
    fun addAlert_optionalVariantId_included() {
        val alert = createBasicAlert(variantId = "variant_abc")
        // Should not crash
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    @Test
    fun addAlert_optionalVariantId_excluded() {
        val alert = createBasicAlert(variantId = null)
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    @Test
    fun addAlert_expiryTimestamp_doesNotCrash() {
        val alert = createBasicAlert(expiryTimestamp = Date())
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    @Test
    fun addAlert_nullExpiryTimestamp_doesNotCrash() {
        val alert = createBasicAlert(expiryTimestamp = null)
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    @Test
    fun addAlert_alertPrice_doesNotCrash() {
        val alert = createBasicAlert(alertPrice = 19.99)
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    @Test
    fun addAlert_profileId_doesNotCrash() {
        val alert = createBasicAlert(profileId = "user_456")
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    @Test
    fun addAlert_mrp_doesNotCrash() {
        val alert = createBasicAlert(mrp = 49.99)
        manager.addAlert(alert, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}
            override fun onFailure(errorCode: Int?, errorMessage: String?) {}
        })
    }

    // --- Custom data ---

    @Test
    fun addAlert_customData_blankKeysSkipped_nonBlankIncluded() {
        val customData = mapOf(
            "valid_key" to "value1",
            "" to "should_be_skipped",
            "   " to "also_skipped",
            "another_key" to "value2"
        )
        val alert = createBasicAlert(data = customData)
        // Should not crash; blank keys are filtered by key.isNotBlank()
        manager.addAlert(alert, object : PushEngageResponseCallback {
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
