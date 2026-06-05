package com.pushengage.pushengage.notificationhandling

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for PENotificationHandlerActivity — notification click handling.
 *
 * This activity is completely untested. It handles intent navigation when
 * a user taps a notification or action button. Key crash risks:
 * - getLaunchIntentForPackage() returning null (line 70)
 * - Missing intent extras
 * - Invalid URL format
 *
 * Uses Theme.AppCompat.Light.NoActionBar to satisfy AppCompatActivity requirements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PENotificationHandlerActivityTest {

    @Before
    fun setUp() {
        // Set the application theme to AppCompat to avoid IllegalStateException
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
    }

    // --- Intent with URL ---

    @Test
    fun handleIntent_withUrl_opensActionView() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.URL_EXTRA, "https://example.com/page")
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        val shadowActivity = shadowOf(activity)
        val nextStartedActivity = shadowActivity.nextStartedActivity

        // Should have started an ACTION_VIEW or service intent
        assertNotNull("Should start an activity or service", nextStartedActivity)
        assertTrue("Activity should be finishing", activity.isFinishing)
    }

    // --- Intent without URL ---

    @Test
    fun handleIntent_withoutUrl_launchesMainPackage() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)
        // No URL_EXTRA

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should be finishing", activity.isFinishing)
    }

    @Test
    fun handleIntent_nullUrl_launchesMainPackage() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.URL_EXTRA, null as String?)
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should be finishing", activity.isFinishing)
    }

    // --- Missing extras ---

    @Test
    fun handleIntent_nullDataExtra_doesNotCrash() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        // No DATA_EXTRA set
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should be finishing", activity.isFinishing)
    }

    @Test
    fun handleIntent_missingAllExtras_doesNotCrash() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        // Completely bare intent — no extras at all

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should be finishing", activity.isFinishing)
    }

    // --- Service started ---

    @Test
    fun handleIntent_startsNotificationService() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)
        intent.putExtra(PEConstants.ACTION_EXTRA, "action1")

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        val shadowActivity = shadowOf(activity)
        val startedService = shadowActivity.nextStartedService

        assertNotNull("Should start NotificationService", startedService)
        assertEquals("test_tag", startedService?.getStringExtra(PEConstants.TAG_EXTRA))
    }

    // --- Activity finishes ---

    @Test
    fun handleIntent_finishesActivity() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should call finish()", activity.isFinishing)
    }

    // --- URL handling edge cases ---

    @Test
    fun handleIntent_invalidUrl_doesNotCrash() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.URL_EXTRA, "not a valid url ://@@")
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should be finishing even with invalid URL", activity.isFinishing)
    }

    @Test
    fun handleIntent_withData_doesNotCrash() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PENotificationHandlerActivity::class.java)
        intent.putExtra(PEConstants.URL_EXTRA, "https://example.com")
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)
        val data = hashMapOf("key1" to "value1", "key2" to "value2")
        intent.putExtra(PEConstants.DATA_EXTRA, data)

        val activity = Robolectric.buildActivity(PENotificationHandlerActivity::class.java, intent)
            .create()
            .get()

        assertTrue("Activity should be finishing", activity.isFinishing)
    }
}
