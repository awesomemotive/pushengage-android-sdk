package com.pushengage.pushengage.iam.util

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.display.IAMDisplayManagerImpl
import com.pushengage.pushengage.iam.display.IAMWebViewContainer
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.repository.IAMRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The activity-lifecycle bridge must never crash regardless of what the host
 * app's activities do — including lifecycle events that arrive before the SDK
 * is initialized — and a rotation (configuration change) must carry the
 * displayed message over to the recreated activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMConfigurationManagerTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var manager: IAMConfigurationManager

    @Before
    fun setUp() {
        PERoomDatabase.getDatabase(app).iamDao().deleteAllMessages()
        manager = IAMConfigurationManager.getInstance()
    }

    @After
    fun tearDown() {
        manager.cleanup(app)
        PERoomDatabase.getDatabase(app).iamDao().deleteAllMessages()
    }

    @Test
    fun `getInstance returns a singleton`() {
        assertSame(IAMConfigurationManager.getInstance(), IAMConfigurationManager.getInstance())
    }

    @Test
    fun `all lifecycle callbacks are safe before initialize`() {
        // The host app's activities start moving before PushEngage.Builder.build()
        // runs — none of these may crash with no display manager wired.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(activity, null)
        manager.onActivityStarted(activity)
        manager.onActivityResumed(activity)
        manager.onActivityPaused(activity)
        manager.onActivityStopped(activity)
        manager.onActivitySaveInstanceState(activity, android.os.Bundle())
        manager.onActivityDestroyed(activity)
    }

    /**
     * Reports a configuration change on demand. `onActivityStopped` branches on
     * [Activity.isChangingConfigurations], and the bug below is about what that
     * branch leaves behind, so the tests need to control it directly rather than
     * hope a Robolectric qualifier change produces the right flag.
     */
    private class ConfigChangingActivity : Activity() {
        var changingConfigurations = false
        override fun isChangingConfigurations(): Boolean = changingConfigurations
    }

    @Test
    fun `rotating and then backgrounding pauses IAM without waiting for a GC`() {
        // onActivityStopped returns early for a config-changing activity, because
        // pausing mid-rotation would dismiss the displayed message. That left the
        // activity in the started set to be reclaimed whenever GC collected the
        // weak key. Until then the set read as non-empty, so the next genuine stop
        // looked like an in-app navigation and IAM never paused: queue processing,
        // auto-dismiss and trigger-delay timers all kept running with the app in
        // the background. There is deliberately no System.gc() in this test — the
        // whole point is that it must not be needed.
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        val first = Robolectric.buildActivity(ConfigChangingActivity::class.java).setup().get()
        manager.onActivityCreated(first, null)
        manager.onActivityStarted(first)

        // Rotate: the old activity stops while changing configurations, then dies.
        first.changingConfigurations = true
        manager.onActivityStopped(first)
        manager.onActivityDestroyed(first)

        val second = Robolectric.buildActivity(ConfigChangingActivity::class.java).setup().get()
        manager.onActivityCreated(second, null)
        manager.onActivityStarted(second)

        // Home pressed: the last started activity stops, for real this time.
        manager.onActivityStopped(second)

        // A paused display manager refuses to display, which is what "backgrounded"
        // means in practice — asserted through behaviour rather than by exposing the
        // flag just for a test.
        assertFalse(
            "IAM must be paused once the app is actually backgrounded",
            displayManager.displayMessage(message("msg-after-background"), second)
        )

        displayManager.shutdown()
    }

    @Test
    fun `a rotation on its own does not pause IAM`() {
        // The other half, and the reason onActivityStopped returns early at all: a
        // rotation is not a backgrounding, so removing the entry in
        // onActivityDestroyed must not make one look like it.
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        val first = Robolectric.buildActivity(ConfigChangingActivity::class.java).setup().get()
        manager.onActivityCreated(first, null)
        manager.onActivityStarted(first)

        first.changingConfigurations = true
        manager.onActivityStopped(first)
        manager.onActivityDestroyed(first)

        val second = Robolectric.buildActivity(ConfigChangingActivity::class.java).setup().get()
        manager.onActivityCreated(second, null)
        manager.onActivityStarted(second)

        assertTrue(
            "IAM must still be running after a rotation",
            displayManager.displayMessage(message("msg-after-rotation"), second)
        )

        displayManager.shutdown()
    }

    @Test
    fun `rotation carries the displayed message over to the recreated activity`() {
        val looper = shadowOf(Looper.getMainLooper())
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        // Portrait activity shows a message.
        val portrait = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(portrait, null)
        assertTrue(displayManager.displayMessage(message("msg-a"), portrait))
        looper.idle()
        assertEquals("msg-a", displayManager.displayingMessageId)

        // Rotate: the system recreates the activity in landscape.
        RuntimeEnvironment.setQualifiers("+land")
        val landscape = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(landscape, null)
        looper.idleFor(Duration.ofSeconds(1))

        assertEquals(
            "message must survive the rotation",
            "msg-a", displayManager.displayingMessageId
        )
        val content = landscape.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue(
            "message container must be attached to the recreated activity",
            (0 until content.childCount).any { content.getChildAt(it) is IAMWebViewContainer }
        )

        displayManager.shutdown()
    }

    @Test
    fun `initialize picks up an activity that was already resumed`() {
        // React Native / Flutter call Builder.build() from JS/Dart, after the host
        // activity has resumed; Android never replays that resume. The tracker saw
        // it, so initialize must replay it — observable here because the first
        // rotation is only detected if the replay seeded the activity and config.
        PEActivityTracker.install(app)
        val looper = shadowOf(Looper.getMainLooper())
        val portrait = Robolectric.buildActivity(Activity::class.java).setup().get()

        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)
        looper.idle()

        assertTrue(displayManager.displayMessage(message("msg-a"), portrait))
        looper.idle()

        RuntimeEnvironment.setQualifiers("+land")
        val landscape = Robolectric.buildActivity(Activity::class.java).setup().get()
        looper.idleFor(Duration.ofSeconds(1))

        val content = landscape.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue(
            "first rotation after a late init must carry the message to the new activity",
            (0 until content.childCount).any { content.getChildAt(it) is IAMWebViewContainer }
        )

        displayManager.shutdown()
    }

    @Test
    fun `same-configuration activity change does not re-drive a configuration change`() {
        val looper = shadowOf(Looper.getMainLooper())
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(first, null)
        assertTrue(displayManager.displayMessage(message("msg-a"), first))
        looper.idle()

        // Plain navigation to a second activity with the SAME configuration.
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(second, null)
        looper.idle()

        assertEquals("msg-a", displayManager.displayingMessageId)
        val firstContent = first.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue(
            "without a config change the container must stay on its original activity",
            (0 until firstContent.childCount).any { firstContent.getChildAt(it) is IAMWebViewContainer }
        )

        displayManager.shutdown()
    }

    @Test
    fun `backgrounding the app dismisses the displayed message`() {
        val looper = shadowOf(Looper.getMainLooper())
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(activity, null)
        manager.onActivityStarted(activity)
        manager.onActivityResumed(activity)
        assertTrue(displayManager.displayMessage(message("msg-a"), activity))
        looper.idle()

        // Real backgrounding: the only started activity pauses AND stops, and
        // no other activity has started.
        manager.onActivityPaused(activity)
        manager.onActivityStopped(activity)
        looper.idleFor(Duration.ofMillis(500))

        assertNull(
            "backgrounding the app must take the message down",
            displayManager.displayingMessageId
        )

        displayManager.shutdown()
    }

    @Test
    fun `navigating to another activity does not dismiss the displayed message`() {
        val looper = shadowOf(Looper.getMainLooper())
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(first, null)
        manager.onActivityStarted(first)
        manager.onActivityResumed(first)
        assertTrue(displayManager.displayMessage(message("msg-a"), first))
        looper.idle()

        // Forward navigation A→B: A pauses, B starts+resumes, THEN A stops —
        // the app never leaves the foreground.
        manager.onActivityPaused(first)
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(second, null)
        manager.onActivityStarted(second)
        manager.onActivityResumed(second)
        manager.onActivityStopped(first)
        looper.idleFor(Duration.ofMillis(500))

        assertEquals(
            "an in-app navigation must not consume the message as \"app_backgrounded\"",
            "msg-a", displayManager.displayingMessageId
        )

        displayManager.shutdown()
    }

    @Test
    fun `backgrounding from the second activity still dismisses the message`() {
        val looper = shadowOf(Looper.getMainLooper())
        val displayManager = IAMDisplayManagerImpl(app, IAMRepository.getInstance(app))
        manager.initialize(app, displayManager)

        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(first, null)
        manager.onActivityStarted(first)
        manager.onActivityResumed(first)
        assertTrue(displayManager.displayMessage(message("msg-a"), first))
        looper.idle()

        // Navigate A→B, then background the app from B.
        manager.onActivityPaused(first)
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.onActivityCreated(second, null)
        manager.onActivityStarted(second)
        manager.onActivityResumed(second)
        manager.onActivityStopped(first)
        looper.idle()

        manager.onActivityPaused(second)
        manager.onActivityStopped(second)
        looper.idleFor(Duration.ofMillis(500))

        assertNull(
            "backgrounding after a navigation must still take the message down",
            displayManager.displayingMessageId
        )

        displayManager.shutdown()
    }

    @Test
    fun `cleanup allows a fresh singleton to be created`() {
        manager.cleanup(app)
        val fresh = IAMConfigurationManager.getInstance()
        // A new instance after cleanup (old one released its state).
        assertTrue(fresh !== manager)
        // Point the member at the fresh instance so tearDown cleans the right one.
        manager = fresh
    }

    private fun message(id: String) = IAMMessage(
        id, IAMPosition.CENTER, "<html><body>hi</body></html>", 0L,
        true, "{}", null, null, 1, null, null, "{\"type\":\"auto\"}"
    )
}
