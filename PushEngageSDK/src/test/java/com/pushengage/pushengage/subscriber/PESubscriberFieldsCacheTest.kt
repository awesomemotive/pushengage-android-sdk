package com.pushengage.pushengage.subscriber

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the subscriber-fields cache living in [PEPrefs].
 *
 * The cache backs the "skip-if-already-in-sync" optimization on
 * `PushEngage.identify(...)` and the "skip-if-no-keys-present" optimization
 * on `PushEngage.logout(...)`. It is also wiped on unsubscribe / hash change
 * (via the existing `clearSiteSpecificData()` path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PESubscriberFieldsCacheTest {

    private lateinit var prefs: PEPrefs

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)
    }

    @Test
    fun emptyCache_returnsEmptyMap() {
        assertTrue(prefs.subscriberFields.isEmpty())
    }

    @Test
    fun merge_writesNewKeys() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com", "profile_id" to "u_1"))
        val out = prefs.subscriberFields
        assertEquals("a@b.com", out["email"])
        assertEquals("u_1", out["profile_id"])
    }

    @Test
    fun merge_upsertsExistingKeys() {
        prefs.mergeSubscriberFields(mapOf("email" to "old@b.com"))
        prefs.mergeSubscriberFields(mapOf("email" to "new@b.com", "first_name" to "Jane"))
        val out = prefs.subscriberFields
        assertEquals("new@b.com", out["email"])
        assertEquals("Jane", out["first_name"])
    }

    @Test
    fun merge_preservesUntouchedKeys() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com", "first_name" to "Jane"))
        prefs.mergeSubscriberFields(mapOf("phone" to "+15551234567"))
        val out = prefs.subscriberFields
        assertEquals("a@b.com", out["email"])
        assertEquals("Jane", out["first_name"])
        assertEquals("+15551234567", out["phone"])
    }

    @Test
    fun merge_storesNumbersAndBooleans() {
        prefs.mergeSubscriberFields(mapOf("zip" to 10001, "first_name" to true))
        val out = prefs.subscriberFields
        // Stored values can be the raw value or its String form (JSONObject behavior);
        // the cache contract is "equality check matches what the consumer wrote".
        // We assert via equals against the original value.
        assertEquals(10001, (out["zip"] as Number).toInt())
        assertEquals(true, out["first_name"])
    }

    @Test
    fun remove_deletesNamedKeys() {
        prefs.mergeSubscriberFields(
            mapOf("email" to "a@b.com", "first_name" to "Jane", "profile_id" to "u_1"),
        )
        prefs.removeSubscriberFields(listOf("first_name", "profile_id"))
        val out = prefs.subscriberFields
        assertEquals("a@b.com", out["email"])
        assertFalse(out.containsKey("first_name"))
        assertFalse(out.containsKey("profile_id"))
    }

    @Test
    fun remove_unknownKeys_noOps() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com"))
        prefs.removeSubscriberFields(listOf("phone"))
        assertEquals("a@b.com", prefs.subscriberFields["email"])
    }

    @Test
    fun clear_wipesEverything() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com", "first_name" to "Jane"))
        prefs.clearSubscriberFields()
        assertTrue(prefs.subscriberFields.isEmpty())
    }

    @Test
    fun clearSiteSpecificData_alsoWipesSubscriberFields() {
        // Subscriber identity is tied to the site/hash; resetting site-scoped state
        // must also wipe the cached personal fields.
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com"))
        prefs.clearSiteSpecificData()
        assertTrue(prefs.subscriberFields.isEmpty())
    }

    // --- TTL / freshness timestamp ---

    @Test
    fun merge_writesTimestamp_approximatelyNow() {
        val before = System.currentTimeMillis()
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com"))
        val ts = prefs.subscriberCacheTimestamp
        val after = System.currentTimeMillis()
        assertTrue("Timestamp $ts should be ≥ before=$before", ts >= before)
        assertTrue("Timestamp $ts should be ≤ after=$after", ts <= after)
    }

    @Test
    fun emptyCache_timestampDefaultsToZero() {
        assertEquals(0L, prefs.subscriberCacheTimestamp)
    }

    @Test
    fun clear_resetsTimestampToZero() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com"))
        assertTrue(prefs.subscriberCacheTimestamp > 0L)
        prefs.clearSubscriberFields()
        assertEquals(0L, prefs.subscriberCacheTimestamp)
    }

    @Test
    fun removeAll_emptiesCacheAndResetsTimestamp() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com", "first_name" to "Jane"))
        prefs.removeSubscriberFields(listOf("email", "first_name"))
        assertTrue(prefs.subscriberFields.isEmpty())
        // Cache became empty, so the freshness timestamp is no longer meaningful.
        assertEquals(0L, prefs.subscriberCacheTimestamp)
    }

    @Test
    fun clearSiteSpecificData_resetsTimestamp() {
        prefs.mergeSubscriberFields(mapOf("email" to "a@b.com"))
        prefs.clearSiteSpecificData()
        assertEquals(0L, prefs.subscriberCacheTimestamp)
    }
}
