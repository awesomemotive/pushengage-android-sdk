package com.pushengage.pushengage.helper

import org.junit.Assert.*
import org.junit.Test

class PEConstantsTest {

    @Test
    fun productionUrls_areValidHttps() {
        val prodUrls = listOf(
            PEConstants.PROD_BASE_URL,
            PEConstants.PROD_BASE_CDN_URL,
            PEConstants.PROD_ANALYTICS_URL,
            PEConstants.PROD_TRIGGER_URL,
            PEConstants.PROD_LOG_URL
        )

        for (url in prodUrls) {
            assertTrue("URL should start with https://: $url", url.startsWith("https://"))
            assertTrue("URL should end with /: $url", url.endsWith("/"))
        }
    }

    @Test
    fun stagingUrls_areValidHttps() {
        val stgUrls = listOf(
            PEConstants.STG_BASE_URL,
            PEConstants.STG_BASE_CDN_URL,
            PEConstants.STG_ANALYTICS_URL,
            PEConstants.STG_TRIGGER_URL,
            PEConstants.STG_LOG_URL
        )

        for (url in stgUrls) {
            assertTrue("URL should start with https://: $url", url.startsWith("https://"))
            assertTrue("URL should end with /: $url", url.endsWith("/"))
        }
    }

    @Test
    fun defaultChannelId_matchesDefaultChannelName() {
        assertEquals("Default Channel", PEConstants.DEFAULT_CHANNEL_ID)
        assertEquals("Default Channel", PEConstants.DEFAULT_CHANNEL_NAME)
        assertEquals(PEConstants.DEFAULT_CHANNEL_ID, PEConstants.DEFAULT_CHANNEL_NAME)
    }

    @Test
    fun retryDelay_isSixtySeconds() {
        assertEquals(60000, PEConstants.RETRY_DELAY)
    }

    @Test
    fun intentExtraKeys_areNotEmpty() {
        assertTrue(PEConstants.URL_EXTRA.isNotEmpty())
        assertTrue(PEConstants.TAG_EXTRA.isNotEmpty())
        assertTrue(PEConstants.DATA_EXTRA.isNotEmpty())
        assertTrue(PEConstants.ID_EXTRA.isNotEmpty())
        assertTrue(PEConstants.ACTION_EXTRA.isNotEmpty())
    }

    @Test
    fun environmentConstants_areDistinct() {
        assertNotEquals(PEConstants.PROD, PEConstants.STG)
        assertEquals("PRODUCTION", PEConstants.PROD)
        assertEquals("STAGING", PEConstants.STG)
    }

    @Test
    fun siteStatusConstants_areCorrect() {
        assertEquals("active", PEConstants.ACTIVE)
        assertEquals("Site not active", PEConstants.SITE_NOT_ACTIVE)
        assertEquals("User not subscribed", PEConstants.USER_NOT_SUBSCRIBED)
        assertEquals("VALID", PEConstants.VALID)
        assertEquals("Internet Not Available", PEConstants.NETWORK_ISSUE)
    }

    @Test
    fun deviceTypeConstants_areCorrect() {
        assertEquals("mobile", PEConstants.MOBILE)
        assertEquals("tablet", PEConstants.TABLET)
        assertEquals("android-sdk", PEConstants.ANDROID_SDK)
        assertEquals("android", PEConstants.ANDROID)
    }

    @Test
    fun endpointTypeConstants_areNotEmpty() {
        assertTrue(PEConstants.BASE.isNotEmpty())
        assertTrue(PEConstants.BASE_CDN.isNotEmpty())
        assertTrue(PEConstants.TRIGGER.isNotEmpty())
        assertTrue(PEConstants.LOG.isNotEmpty())
        assertTrue(PEConstants.ANALYTICS.isNotEmpty())
    }

    @Test
    fun errorTrackingConstants_areNotEmpty() {
        assertTrue(PEConstants.RECORD_SUBSCRIPTION_FAILED.isNotEmpty())
        assertTrue(PEConstants.NOTIFICATION_REFETCH_FAILED.isNotEmpty())
        assertTrue(PEConstants.VIEW_COUNT_TRACKING_FAILED.isNotEmpty())
        assertTrue(PEConstants.CLICK_COUNT_TRACKING_FAILED.isNotEmpty())
    }

    @Test
    fun sdkVersion_isNotEmpty() {
        assertTrue(PEConstants.SDK_VERSION.isNotEmpty())
    }
}
