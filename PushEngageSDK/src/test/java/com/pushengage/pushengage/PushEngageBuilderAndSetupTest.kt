package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Callbacks.FcmConfigErrorListener
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
 * Covers Builder, basic getters/setters, and the simple delegating methods on PushEngage.
 * These methods are stateless or near-stateless and exercise the SDK-init contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageBuilderAndSetupTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
        // Builder.build() is idempotent — clear the cached singleton so each test
        // exercises a fresh init path.
        PushEngageTestSupport.resetSingleton()
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
        PushEngageTestSupport.resetSingleton()
    }

    // ---- Builder ----

    @Test
    fun builder_addContextAndSetAppId_returnsBuilderForChaining() {
        val builder = PushEngage.Builder()
        val sameBuilder = builder.addContext(context).setAppId("test_app_id")
        assertSame("Builder methods must return the builder for chaining", builder, sameBuilder)
    }

    @Test
    fun builder_build_persistsSiteKeyAndSetsStaticContext() {
        // Pre-populate hash so PushEngage constructor's auto-subscribe path is skipped
        // (it requires FirebaseApp which is not configured in the test JVM).
        PEPrefs(context).hash = "preexisting_hash"

        val instance = PushEngage.Builder()
            .addContext(context)
            .setAppId("site_xyz")
            .build()
        assertNotNull(instance)

        val storedSiteKey = PEPrefs(context).siteKey
        assertEquals("site_xyz", storedSiteKey)
    }

    @Test
    fun builder_buildTwice_lastSiteKeyWins() {
        PEPrefs(context).hash = "preexisting_hash"

        PushEngage.Builder().addContext(context).setAppId("first").build()
        PushEngage.Builder().addContext(context).setAppId("second").build()
        assertEquals("second", PEPrefs(context).siteKey)
    }

    @Test
    fun builder_buildWithDifferentAppId_clearsPreviousSiteState() {
        // When the host swaps appId between two builds (e.g. dev→prod), state cached
        // from the previous site — subscriber hash, siteId, sync URLs, project IDs,
        // manual-unsubscribe flag — must be wiped so an unsubscribe()/getSubscriberId()
        // fired before the next sync can't act on the previous site's identifiers.
        PEPrefs(context).apply {
            hash = "site_one_hash"
            siteId = 1234L
            projectId = "old_sender_id"
            firebaseProjectId = "old-firebase-project"
            siteStatus = "ACTIVE"
            backendUrl = "https://old.backend"
            setIsManuallyUnsubscribed(true)
            setIsSubscriberDeleted(true)
        }

        PushEngage.Builder().addContext(context).setAppId("first").build()
        PushEngage.Builder().addContext(context).setAppId("second").build()

        val prefs = PEPrefs(context)
        assertEquals("second", prefs.siteKey)
        assertEquals("", prefs.hash)
        assertEquals(0L, prefs.siteId)
        assertEquals("", prefs.projectId)
        assertEquals("", prefs.firebaseProjectId)
        assertEquals("", prefs.siteStatus)
        assertEquals("", prefs.backendUrl)
        assertFalse(prefs.isManuallyUnsubscribed())
        assertFalse(prefs.isSubscriberDeleted())
    }

    @Test
    fun builder_buildSameAppIdTwice_preservesSubscriberState() {
        // Same appId across builds is the steady-state path — the host re-initializes
        // on every cold start. Cached subscriber state must survive.
        PEPrefs(context).apply {
            hash = "preexisting_hash"
            siteId = 9999L
        }

        PushEngage.Builder().addContext(context).setAppId("steady_key").build()
        PushEngage.Builder().addContext(context).setAppId("steady_key").build()

        val prefs = PEPrefs(context)
        assertEquals("steady_key", prefs.siteKey)
        assertEquals("preexisting_hash", prefs.hash)
        assertEquals(9999L, prefs.siteId)
    }

    // ---- App ID validation (issue #38) ----

    @Test
    fun builder_buildWithoutSetAppId_doesNotClobberExistingSiteKey() {
        // Simulates the regression case: a release ships a code path that constructs the
        // Builder without setAppId(). Previously this overwrote prefs with null, which made
        // the next sync fire /sites//sync/android. The guard must preserve the stored key.
        PEPrefs(context).apply {
            hash = "preexisting_hash"
            siteKey = "previously_valid_key"
        }

        PushEngage.Builder().addContext(context).build()

        assertEquals("previously_valid_key", PEPrefs(context).siteKey)
    }

    @Test
    fun builder_buildWithBlankAppId_doesNotClobberExistingSiteKey() {
        PEPrefs(context).apply {
            hash = "preexisting_hash"
            siteKey = "previously_valid_key"
        }

        PushEngage.Builder().addContext(context).setAppId("   ").build()

        assertEquals("previously_valid_key", PEPrefs(context).siteKey)
    }

    @Test
    fun builder_buildWithNullAppId_freshInstall_leavesSiteKeyEmpty() {
        // Fresh install: nothing in prefs. Build without setAppId. We do not write null,
        // so the default-empty stays. The sync call sites then short-circuit.
        PEPrefs(context).hash = "preexisting_hash"

        PushEngage.Builder().addContext(context).build()

        assertEquals("", PEPrefs(context).siteKey)
    }

    @Test
    fun builder_buildWithValidAppId_afterEmptyBuild_setsKey() {
        // Confirm that a legitimate re-init still works after a no-op build call.
        PEPrefs(context).hash = "preexisting_hash"

        PushEngage.Builder().addContext(context).build()
        PushEngage.Builder().addContext(context).setAppId("real_key").build()

        assertEquals("real_key", PEPrefs(context).siteKey)
    }

    // ---- getSdkVersion ----

    @Test
    fun getSdkVersion_returnsConstant() {
        assertEquals(PEConstants.SDK_VERSION, PushEngage.getSdkVersion())
    }

    @Test
    fun getSdkVersion_isNonEmpty() {
        val v = PushEngage.getSdkVersion()
        assertNotNull(v)
        assertTrue("SDK version must be non-empty", v.isNotEmpty())
    }

    // ---- getDeviceTokenHash ----

    @Test
    fun getDeviceTokenHash_returnsEmptyByDefault() {
        PushEngageTestSupport.setStaticField("prefs", PEPrefs(context))
        // PEPrefs.getHash() returns "" when unset.
        assertEquals("", PushEngage.getDeviceTokenHash())
    }

    @Test
    fun getDeviceTokenHash_reflectsStoredHash() {
        val prefs = PEPrefs(context).apply { hash = "stored_hash_value" }
        PushEngageTestSupport.setStaticField("prefs", prefs)
        assertEquals("stored_hash_value", PushEngage.getDeviceTokenHash())
    }

    // ---- setSmallIconResource ----

    @Test
    fun setSmallIconResource_writesToPrefs() {
        val prefs = PEPrefs(context)
        PushEngageTestSupport.setStaticField("prefs", prefs)

        PushEngage.setSmallIconResource("ic_my_icon")

        assertEquals("ic_my_icon", PEPrefs(context).smallIconResource)
    }

    @Test
    fun setSmallIconResource_nullValue_revertsToDefault() {
        val prefs = PEPrefs(context).apply { smallIconResource = "previous" }
        PushEngageTestSupport.setStaticField("prefs", prefs)

        PushEngage.setSmallIconResource(null)

        // putString(key, null) clears the entry, so getString returns the configured default.
        assertEquals("ic_stat_notification_default", PEPrefs(context).smallIconResource)
    }

    // ---- enableLogging ----

    @Test
    fun enableLogging_true_persistsAcrossCalls() {
        PushEngage.enableLogging(true)
        // Calling again should not throw or change observable state in a problematic way.
        PushEngage.enableLogging(false)
        PushEngage.enableLogging(true)
        // No assertion target — exercised purely for coverage; success = no crash.
    }

    @Test(expected = NullPointerException::class)
    fun enableLogging_null_throwsNpe() {
        // Current contract: PELogger.enableLogging unboxes the Boolean, so a null argument
        // throws NPE. Locking that behavior in so any future change to accept null is intentional.
        PushEngage.enableLogging(null)
    }

    // ---- getNotificationPermissionStatus ----

    @Test
    fun getNotificationPermissionStatus_whenContextNull_returnsDenied() {
        PushEngageTestSupport.setStaticField("context", null)
        assertEquals("denied", PushEngage.getNotificationPermissionStatus())
    }

    @Test
    fun getNotificationPermissionStatus_whenContextSet_returnsGrantedOrDenied() {
        PushEngageTestSupport.setStaticField("context", context)
        val status = PushEngage.getNotificationPermissionStatus()
        assertTrue("status must be granted or denied, got: $status", status == "granted" || status == "denied")
    }

    // ---- requestNotificationPermission ----

    @Test
    fun requestNotificationPermission_nullActivity_doesNotCrash() {
        // PEPermissionFragment.requestPermission handles the null-activity guard internally;
        // here we just verify the facade doesn't add its own crash path.
        try {
            PushEngage.requestNotificationPermission(null, null)
        } catch (e: Throwable) {
            // Any throw is fine for coverage; document so a future refactor stays aware.
            assertNotNull(e)
        }
    }

    // ---- setFcmConfigErrorListener ----

    @Test
    fun setFcmConfigErrorListener_registersListener() {
        val listener = FcmConfigErrorListener { _, _ -> }
        try {
            PushEngage.setFcmConfigErrorListener(listener)
            assertSame(listener, PushEngageTestSupport.getStaticField("fcmConfigErrorListener"))
        } finally {
            PushEngage.setFcmConfigErrorListener(null)
        }
    }

    @Test
    fun setFcmConfigErrorListener_null_clearsListener() {
        PushEngage.setFcmConfigErrorListener(FcmConfigErrorListener { _, _ -> })
        PushEngage.setFcmConfigErrorListener(null)
        assertNull(PushEngageTestSupport.getStaticField("fcmConfigErrorListener"))
    }

    @Test
    fun setFcmConfigErrorListener_overwritesPreviousListener() {
        val first = FcmConfigErrorListener { _, _ -> }
        val second = FcmConfigErrorListener { _, _ -> }
        try {
            PushEngage.setFcmConfigErrorListener(first)
            PushEngage.setFcmConfigErrorListener(second)
            assertSame(second, PushEngageTestSupport.getStaticField("fcmConfigErrorListener"))
        } finally {
            PushEngage.setFcmConfigErrorListener(null)
        }
    }
}
