package com.pushengage.pushengage.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEPrefsTest {

    private lateinit var prefs: PEPrefs
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear shared prefs before each test
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)
    }

    // --- Default value tests ---

    @Test
    fun defaultValues_deviceToken_isEmpty() {
        assertEquals("", prefs.deviceToken)
    }

    @Test
    fun defaultValues_hash_isEmpty() {
        assertEquals("", prefs.hash)
    }

    @Test
    fun defaultValues_siteStatus_isEmpty() {
        assertEquals("", prefs.siteStatus)
    }

    @Test
    fun defaultValues_environment_isProduction() {
        assertEquals(PEConstants.PROD, prefs.environment)
    }

    @Test
    fun defaultValues_isNotificationDisabled_isZero() {
        assertEquals(0L, prefs.isNotificationDisabled())
    }

    @Test
    fun defaultValues_isSubscriberDeleted_isFalse() {
        assertFalse(prefs.isSubscriberDeleted())
    }

    @Test
    fun defaultValues_isManuallyUnsubscribed_isFalse() {
        assertFalse(prefs.isManuallyUnsubscribed())
    }

    @Test
    fun defaultValues_smallIconResource_isDefault() {
        assertEquals("ic_stat_notification_default", prefs.smallIconResource)
    }

    @Test
    fun defaultValues_siteId_isZero() {
        assertEquals(0L, prefs.siteId)
    }

    @Test
    fun defaultValues_payload_isEmpty() {
        assertEquals("", prefs.payload)
    }

    @Test
    fun defaultValues_projectId_isEmpty() {
        assertEquals("", prefs.projectId)
    }

    @Test
    fun defaultValues_siteKey_isEmpty() {
        assertEquals("", prefs.siteKey)
    }

    @Test
    fun defaultValues_geoFetch_isFalse() {
        assertFalse(prefs.isGeoFetch)
    }

    @Test
    fun defaultValues_eu_isZero() {
        assertEquals(0L, prefs.eu)
    }

    @Test
    fun defaultValues_deleteOnNotificationDisable_isFalse() {
        assertFalse(prefs.deleteOnNotificationDisable)
    }

    // --- Set and get round trip tests ---

    @Test
    fun setAndGet_deviceToken_roundTrips() {
        prefs.deviceToken = "test_token_12345"
        assertEquals("test_token_12345", prefs.deviceToken)
    }

    @Test
    fun setAndGet_hash_roundTrips() {
        prefs.hash = "subscriber_hash_abc"
        assertEquals("subscriber_hash_abc", prefs.hash)
    }

    @Test
    fun setAndGet_siteId_roundTrips() {
        prefs.siteId = 12345L
        assertEquals(12345L, prefs.siteId)
    }

    @Test
    fun setAndGet_allUrlFields_roundTrip() {
        prefs.backendUrl = "https://backend.test.com/"
        prefs.backendCdnUrl = "https://cdn.test.com/"
        prefs.analyticsUrl = "https://analytics.test.com/"
        prefs.triggerUrl = "https://trigger.test.com/"
        prefs.loggerUrl = "https://log.test.com/"

        assertEquals("https://backend.test.com/", prefs.backendUrl)
        assertEquals("https://cdn.test.com/", prefs.backendCdnUrl)
        assertEquals("https://analytics.test.com/", prefs.analyticsUrl)
        assertEquals("https://trigger.test.com/", prefs.triggerUrl)
        assertEquals("https://log.test.com/", prefs.loggerUrl)
    }

    @Test
    fun setAndGet_booleanFlags_roundTrip() {
        prefs.setIsSubscriberDeleted(true)
        prefs.setIsManuallyUnsubscribed(true)
        prefs.deleteOnNotificationDisable = true

        assertTrue(prefs.isSubscriberDeleted())
        assertTrue(prefs.isManuallyUnsubscribed())
        assertTrue(prefs.deleteOnNotificationDisable)
    }

    @Test
    fun setAndGet_longFlags_roundTrip() {
        prefs.setIsNotificationDisabled(1L)
        prefs.eu = 1L

        assertEquals(1L, prefs.isNotificationDisabled())
        assertEquals(1L, prefs.eu)
    }

    @Test
    fun setAndGet_siteStatus_roundTrips() {
        prefs.siteStatus = "active"
        assertEquals("active", prefs.siteStatus)
    }

    @Test
    fun setAndGet_environment_roundTrips() {
        prefs.environment = PEConstants.STG
        assertEquals(PEConstants.STG, prefs.environment)
    }

    @Test
    fun setAndGet_smallIconResource_roundTrips() {
        prefs.smallIconResource = "custom_icon"
        assertEquals("custom_icon", prefs.smallIconResource)
    }

    @Test
    fun setAndGet_payload_roundTrips() {
        prefs.payload = "{\"key\":\"value\"}"
        assertEquals("{\"key\":\"value\"}", prefs.payload)
    }

    @Test
    fun setAndGet_optinUrl_roundTrips() {
        prefs.optinUrl = "https://optin.test.com/"
        assertEquals("https://optin.test.com/", prefs.optinUrl)
    }

    @Test
    fun setAndGet_geoFetch_roundTrips() {
        prefs.setGeoFetch(true)
        assertTrue(prefs.isGeoFetch)
    }

    @Test
    fun setAndGet_actionButtonReceiverRegistered_roundTrips() {
        prefs.setActionButtonReceiverRegistered(true)
        assertTrue(prefs.isActionButtonReceiverRegistered)
    }

    @Test
    fun overwriteValue_replacesOldValue() {
        prefs.deviceToken = "token_v1"
        assertEquals("token_v1", prefs.deviceToken)

        prefs.deviceToken = "token_v2"
        assertEquals("token_v2", prefs.deviceToken)
    }

    @Test
    fun defaultValues_badgeCount_isZero() {
        assertEquals(0, prefs.badgeCount)
    }

    @Test
    fun setAndGet_badgeCount_roundTrips() {
        prefs.badgeCount = 7
        assertEquals(7, prefs.badgeCount)
    }

    @Test
    fun setAndGet_badgeCount_zeroOverwritesPositive() {
        prefs.badgeCount = 5
        prefs.badgeCount = 0
        assertEquals(0, prefs.badgeCount)
    }
}
