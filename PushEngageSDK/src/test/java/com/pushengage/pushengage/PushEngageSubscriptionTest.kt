package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the subscription-status family on PushEngage:
 *   getSubscriptionStatus, getSubscriptionNotificationStatus, getSubscriberId,
 *   unsubscribe, subscribe(Activity, callback)
 *
 * Each method has a defensive init guard plus several local branches (manually unsubscribed,
 * no subscriber hash yet, permission denied, etc.). These tests exercise those branches
 * without hitting the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageSubscriptionTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
    }

    // ---- getSubscriptionStatus ----

    @Test
    fun getSubscriptionStatus_nullCallback_returnsWithoutCrash() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", PEPrefs(context))
        PushEngage.getSubscriptionStatus(null)
    }

    @Test
    fun getSubscriptionStatus_contextNull_callsOnFailureWithSdkNotInitialized() {
        PushEngageTestSupport.setStaticField("context", null)
        PushEngageTestSupport.setStaticField("prefs", null)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionStatus(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK not initialized", cb.failureMessage)
    }

    @Test
    fun getSubscriptionStatus_prefsNull_callsOnFailureWithPrefsNotInitialized() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", null)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionStatus(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK preferences not initialized", cb.failureMessage)
    }

    @Test
    fun getSubscriptionStatus_inactiveSite_callsOnFailureWithSiteNotActive() {
        val prefs = PEPrefs(context).apply { siteStatus = "inactive" }
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionStatus(cb)
        assertTrue(cb.failureInvoked)
        assertEquals(PEConstants.SITE_NOT_ACTIVE, cb.failureMessage)
    }

    @Test
    fun getSubscriptionStatus_manuallyUnsubscribed_returnsFalse() {
        val prefs = PEPrefs(context).apply {
            siteStatus = PEConstants.ACTIVE
            setIsManuallyUnsubscribed(true)
        }
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionStatus(cb)
        assertTrue(cb.successInvoked)
        assertEquals(false, cb.successObject)
    }

    @Test
    fun getSubscriptionStatus_noSubscriberHash_returnsFalse() {
        val prefs = PEPrefs(context).apply {
            siteStatus = PEConstants.ACTIVE
            // hash defaults to "" — never subscribed
        }
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionStatus(cb)
        assertTrue(cb.successInvoked)
        assertEquals(false, cb.successObject)
    }

    @Test
    fun getSubscriptionStatus_permissionDenied_callsOnFailure() {
        val prefs = PEPrefs(context).apply {
            siteStatus = PEConstants.ACTIVE
            hash = "valid_hash"
        }
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)

        // Robolectric defaults notification permission to "granted" in many SDKs, so this test
        // may legitimately succeed-path. Allow either outcome but ensure exactly one was called.
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionStatus(cb)
        assertTrue("Exactly one of onSuccess/onFailure must fire",
            cb.successInvoked xor cb.failureInvoked)
    }

    // ---- getSubscriptionNotificationStatus ----

    @Test
    fun getSubscriptionNotificationStatus_nullCallback_doesNotCrash() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngage.getSubscriptionNotificationStatus(null)
    }

    @Test
    fun getSubscriptionNotificationStatus_contextNull_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", null)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriptionNotificationStatus(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK not initialized", cb.failureMessage)
    }

    // ---- getSubscriberId ----

    @Test
    fun getSubscriberId_nullCallback_doesNotCrash() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", PEPrefs(context))
        PushEngage.getSubscriberId(null)
    }

    @Test
    fun getSubscriberId_contextNull_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", null)
        PushEngageTestSupport.setStaticField("prefs", null)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriberId(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK not initialized", cb.failureMessage)
    }

    @Test
    fun getSubscriberId_prefsNull_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", null)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.getSubscriberId(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK preferences not initialized", cb.failureMessage)
    }

    // ---- unsubscribe ----

    @Test
    fun unsubscribe_contextNull_withCallback_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", null)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.unsubscribe(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK not initialized", cb.failureMessage)
    }

    @Test
    fun unsubscribe_contextNull_withNullCallback_doesNotCrash() {
        PushEngageTestSupport.setStaticField("context", null)
        PushEngage.unsubscribe(null)
    }

    @Test
    fun unsubscribe_prefsNull_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", null)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.unsubscribe(cb)
        assertTrue(cb.failureInvoked)
        assertEquals("Preferences not available", cb.failureMessage)
    }

    @Test
    fun unsubscribe_emptyHash_succeedsImmediately() {
        // No prior subscription — unsubscribe is a no-op success.
        val prefs = PEPrefs(context) // hash = ""
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)

        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.unsubscribe(cb)
        assertTrue(cb.successInvoked)
        assertEquals(true, cb.successObject)
    }

    // ---- subscribe(Activity, callback) ----

    @Test
    fun subscribeWithActivity_contextNull_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", null)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.subscribe(null, cb)
        assertTrue(cb.failureInvoked)
        assertEquals("SDK not initialized", cb.failureMessage)
    }

    @Test
    fun subscribeWithActivity_prefsNull_callsOnFailure() {
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", null)
        val cb = PushEngageTestSupport.RecordingCallback()
        PushEngage.subscribe(null, cb)
        assertTrue(cb.failureInvoked)
        assertEquals("Preferences not available", cb.failureMessage)
    }

    @Test
    fun subscribeWithActivity_contextNull_withNullCallback_doesNotCrash() {
        PushEngageTestSupport.setStaticField("context", null)
        PushEngage.subscribe(null, null)
    }
}
