package com.pushengage.pushengage.iam.util

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tracker is what lets a late `Builder.build()` (React Native / Flutter) find
 * the host activity, so it must report exactly the activity that is resumed right
 * now — never a paused, finishing or destroyed one, which IAM would then try to
 * display into.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEActivityTrackerTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        PEActivityTracker.install(app)
    }

    @Test
    fun `reports the resumed activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertSame(activity, PEActivityTracker.resumedActivity())
    }

    @Test
    fun `reports nothing once the activity pauses`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause()
        assertNull(PEActivityTracker.resumedActivity())
    }

    @Test
    fun `reports nothing for a finishing activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.finish()
        assertNull(PEActivityTracker.resumedActivity())
    }

    @Test
    fun `reports nothing once the activity is destroyed`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop().destroy()
        assertNull(PEActivityTracker.resumedActivity())
    }

    @Test
    fun `follows navigation to the next activity`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        first.pause()
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        first.stop()
        assertSame(second, PEActivityTracker.resumedActivity())
    }

    @Test
    fun `installing twice on the same application is harmless`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // A second install must neither throw nor reset what is already tracked.
        PEActivityTracker.install(app)
        assertSame(activity, PEActivityTracker.resumedActivity())
    }
}
