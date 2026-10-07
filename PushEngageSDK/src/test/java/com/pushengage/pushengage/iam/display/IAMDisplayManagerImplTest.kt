package com.pushengage.pushengage.iam.display

import android.app.Activity
import android.util.AndroidRuntimeException
import android.view.ViewGroup
import android.webkit.WebView
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.repository.IAMRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Guards the auto-dismiss timer lifecycle: a displayDuration timer belongs to
 * exactly one display session. A stale timer left over from an
 * already-dismissed message must never fire into (and dismiss) the message
 * displayed after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMDisplayManagerImplTest {

    private lateinit var activity: Activity
    private lateinit var displayManager: IAMDisplayManagerImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PERoomDatabase.getDatabase(context).iamDao().deleteAllMessages()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        displayManager = IAMDisplayManagerImpl(context, IAMRepository.getInstance(context))
    }

    @After
    fun tearDown() {
        displayManager.shutdown()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PERoomDatabase.getDatabase(context).iamDao().deleteAllMessages()
    }

    private fun message(id: String, displayDurationSeconds: Long) = IAMMessage(
        id, IAMPosition.CENTER, "<html><body>hi</body></html>", displayDurationSeconds,
        true, "{}", null, null, 1, null, null, "{\"type\":\"auto\"}"
    )

    // ------------------------------------------------ missing WebView provider

    @Test
    fun `a device without a usable WebView provider skips the message instead of crashing`() {
        // Devices with the WebView package missing, disabled or mid-update throw from the
        // WebView constructor. The host app must not crash; the message is simply skipped.
        Mockito.mockConstruction(WebView::class.java) { _, _ ->
            throw AndroidRuntimeException(
                "android.webkit.WebViewFactory\$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed"
            )
        }.use {
            val displayed = displayManager.displayMessage(message("msg-no-webview", 0), activity)
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("a message cannot be displayed without a WebView", displayed)
            assertNull("nothing may be marked as displaying", displayManager.displayingMessageId)
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertEquals("nothing may be attached to the view tree", 0, content.childCount)
        }
    }

    // ------------------------------------------------ dead-activity protection

    @Test
    fun `a destroyed activity is not used to display a message`() {
        // Nothing downstream fails on a destroyed activity: findViewById(content)
        // still returns the detached content view, so without an explicit check the
        // WebView is attached to a view tree nobody can see and isDisplaying is set
        // — an invisible message that blocks every queued message behind it.
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val dead = controller.get()
        controller.pause().stop().destroy()
        val content = dead.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue("premise: a destroyed activity still hands out its content view", content != null)

        val displayed = displayManager.displayMessage(message("msg-dead", 0), dead)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("must refuse a destroyed activity", displayed)
        assertNull("nothing may be marked as displaying", displayManager.displayingMessageId)
        assertEquals("nothing may be attached to the dead view tree", 0, content!!.childCount)
    }

    @Test
    fun `a finishing activity is not used to display a message`() {
        // The other half: a finishing activity has not been destroyed yet, so
        // isDestroyed() alone would let this through.
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val finishing = controller.get()
        finishing.finish()
        assertTrue("premise: finishing but not destroyed", finishing.isFinishing)

        val displayed = displayManager.displayMessage(message("msg-finishing", 0), finishing)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("must refuse a finishing activity", displayed)
        assertNull(displayManager.displayingMessageId)
    }

    @Test
    fun `refusing a dead activity leaves the manager free for the next one`() {
        // The failure this protects against was not just one lost message: a stuck
        // isDisplaying blocked the queue for the rest of the session. After a
        // refusal the manager must still accept a live activity.
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val dead = controller.get()
        controller.pause().stop().destroy()
        displayManager.displayMessage(message("msg-dead", 0), dead)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(displayManager.displayMessage(message("msg-live", 0), activity))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("msg-live", displayManager.displayingMessageId)
    }

    @Test
    fun `an activity destroyed after being handed over is refused at attach time`() {
        // The real ordering: the activity is alive when the message is handed to the
        // display manager and dies before the posted display work runs. This is why
        // the check has to sit immediately before the attach, not only at the entry
        // point — displayMessage() returns "scheduled" here, not "displayed".
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val doomed = controller.get()
        val content = doomed.findViewById<android.view.ViewGroup>(android.R.id.content)

        val scheduled = displayManagerOffMainThread(message("msg-race", 0), doomed)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the hand-over itself succeeds — the activity was alive then", scheduled)
        assertNull("but the attach must not happen", displayManager.displayingMessageId)
        assertEquals(0, content!!.childCount)
    }

    /**
     * Calls displayMessage from a non-main thread, so the display manager posts the
     * attach back to the main looper instead of doing it inline — the production
     * ordering, since the controller dispatches display work to a background
     * executor.
     */
    private fun displayManagerOffMainThread(message: IAMMessage, activity: Activity): Boolean {
        var result = false
        val thread = Thread { result = displayManager.displayMessage(message, activity) }
        thread.start()
        thread.join(5_000)
        return result
    }

    @Test
    fun `stale auto-dismiss timer from a dismissed message does not dismiss the next message`() {
        val looper = shadowOf(Looper.getMainLooper())

        // Message A schedules a 10s auto-dismiss.
        assertTrue(displayManager.displayMessage(message("msg-a", 10), activity))
        looper.idle()
        assertEquals("msg-a", displayManager.displayingMessageId)

        // The user dismisses A well before its timer fires.
        displayManager.dismissCurrentMessage()
        looper.idleFor(Duration.ofMillis(500)) // let the exit animation finish
        assertNull("A should be gone after user dismissal", displayManager.displayingMessageId)

        // Message B displays with no auto-dismiss of its own.
        assertTrue(displayManager.displayMessage(message("msg-b", 0), activity))
        looper.idle()
        assertEquals("msg-b", displayManager.displayingMessageId)

        // Advance past A's 10s deadline: A's timer must not fire into B.
        looper.idleFor(Duration.ofSeconds(11))
        assertEquals(
            "B must still be on screen — A's stale timer may not dismiss it",
            "msg-b", displayManager.displayingMessageId
        )
    }

    @Test
    fun `message with a displayDuration auto-dismisses itself`() {
        val looper = shadowOf(Looper.getMainLooper())

        assertTrue(displayManager.displayMessage(message("msg-a", 2), activity))
        looper.idle()
        assertEquals("msg-a", displayManager.displayingMessageId)

        // Past the 2s duration plus the exit animation.
        looper.idleFor(Duration.ofSeconds(3))
        assertNull("message should have auto-dismissed", displayManager.displayingMessageId)
    }

    // ------------------------------------------------------------ state guards

    @Test
    fun `dismiss with nothing displayed is a safe no-op`() {
        displayManager.dismissCurrentMessage()
        displayManager.dismissCurrentMessage()
        assertNull(displayManager.displayingMessageId)
    }

    @Test
    fun `null message is rejected`() {
        assertFalse(displayManager.displayMessage(null, activity))
    }

    @Test
    fun `second message is rejected while the first is on screen`() {
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        assertFalse(
            "concurrent display must be rejected",
            displayManager.displayMessage(message("msg-b", 0), activity)
        )
        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    @Test
    fun `display while paused is rejected and display works again after resume`() {
        val looper = shadowOf(Looper.getMainLooper())
        displayManager.pause()
        assertFalse(displayManager.displayMessage(message("msg-a", 0), activity))

        displayManager.resume()
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()
        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    @Test
    fun `pause while displaying dismisses the current message`() {
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        displayManager.pause() // app backgrounded
        looper.idleFor(Duration.ofMillis(500)) // exit animation

        assertNull("backgrounding must take the message down", displayManager.displayingMessageId)
    }

    @Test
    fun `completion listener fires exactly once per dismissal`() {
        val looper = shadowOf(Looper.getMainLooper())
        var completions = 0
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()
        displayManager.setMessageCompletionListener { completions++ }

        displayManager.dismissCurrentMessage()
        displayManager.dismissCurrentMessage() // double-tap on close: must not double-fire
        looper.idleFor(Duration.ofMillis(500))

        assertEquals(1, completions)
    }

    @Test
    fun `displayMessage called from a background thread displays on the main thread`() {
        val looper = shadowOf(Looper.getMainLooper())
        val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            scheduled.set(displayManager.displayMessage(message("msg-a", 0), activity))
        }
        thread.start()
        thread.join(5_000)

        assertTrue("background-thread display must be accepted (scheduled)", scheduled.get())
        looper.idle()
        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    // ----------------------------------------------------------------- actions

    @Test
    fun `unknown action id is ignored without crashing and the message stays up`() {
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        displayManager.handleAction("no-such-action")
        looper.idle()

        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    @Test
    fun `handleAction with nothing displayed is a safe no-op`() {
        displayManager.handleAction("btn-1")
    }

    @Test
    fun `dismiss action from message HTML dismisses the message`() {
        val looper = shadowOf(Looper.getMainLooper())
        val msg = messageWithActions(
            "msg-a", """{"close":{"type":"dismiss","label":"Close"}}"""
        )
        assertTrue(displayManager.displayMessage(msg, activity))
        looper.idle()

        displayManager.handleAction("close")
        looper.idleFor(Duration.ofMillis(500))

        assertNull(displayManager.displayingMessageId)
    }

    @Test
    fun `custom action notifies the app listener and auto-dismisses`() {
        val looper = shadowOf(Looper.getMainLooper())
        var receivedActionId: String? = null
        displayManager.setCustomActionHandler(object :
            com.pushengage.pushengage.iam.action.IAMCustomActionHandler {
            override fun onCustomAction(actionId: String, parameters: Map<String, Any>) {
                receivedActionId = actionId
            }
        })
        val msg = messageWithActions(
            "msg-a", """{"buy":{"type":"custom","parameters":{"sku":"42"},"label":"Buy"}}"""
        )
        assertTrue(displayManager.displayMessage(msg, activity))
        looper.idle()

        displayManager.handleAction("buy")
        looper.idleFor(Duration.ofMillis(500))

        assertEquals("buy", receivedActionId)
        assertNull("handled action must dismiss the message", displayManager.displayingMessageId)
    }

    @Test
    fun `recognised action that fails to execute still dismisses the message`() {
        // A rejected open_url (non-http scheme) returns handled = false. The button was
        // still a real, recognised button, so the card must close: otherwise a campaign
        // whose only button has a bad URL and no close block traps the user — taps are
        // consumed by the container, so nothing else can shut it.
        val looper = shadowOf(Looper.getMainLooper())
        val msg = messageWithActions(
            "msg-a", """{"bad":{"type":"open_url","parameters":{"url":"mailto:a@b.com"},"label":"Mail"}}"""
        )
        assertTrue(displayManager.displayMessage(msg, activity))
        looper.idle()

        displayManager.handleAction("bad")
        looper.idleFor(Duration.ofMillis(500))

        assertNull(
            "an unexecutable but recognised action must still dismiss",
            displayManager.displayingMessageId
        )
    }

    @Test
    fun `action with unknown type in the JSON does not crash and keeps the message up`() {
        val looper = shadowOf(Looper.getMainLooper())
        val msg = messageWithActions(
            "msg-a", """{"weird":{"type":"teleport","label":"??"}}"""
        )
        assertTrue(displayManager.displayMessage(msg, activity))
        looper.idle()

        displayManager.handleAction("weird")
        looper.idle()

        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    // --------------------------------------------------------------- lifecycle

    @Test
    fun `every message position displays without crashing`() {
        val looper = shadowOf(Looper.getMainLooper())
        for (position in IAMPosition.values()) {
            val msg = IAMMessage(
                "msg-$position", position, "<html><body>x</body></html>", 0L,
                true, "{}", null, null, 1, null, null, "{\"type\":\"auto\"}"
            )
            assertTrue("$position should display", displayManager.displayMessage(msg, activity))
            looper.idle()
            assertEquals("msg-$position", displayManager.displayingMessageId)

            displayManager.dismissCurrentMessage()
            looper.idleFor(Duration.ofMillis(1500)) // exit animation + entry fallback timers
        }
    }

    @Test
    fun `empty and giant html content display without crashing`() {
        val looper = shadowOf(Looper.getMainLooper())

        val empty = IAMMessage(
            "empty", IAMPosition.CENTER, "", 0L, true, "{}",
            null, null, 1, null, null, "{\"type\":\"auto\"}"
        )
        assertTrue(displayManager.displayMessage(empty, activity))
        looper.idle()
        displayManager.dismissCurrentMessage()
        looper.idleFor(Duration.ofMillis(1500))

        val giant = IAMMessage(
            "giant", IAMPosition.CENTER,
            "<html><body>" + "x".repeat(500_000) + "</body></html>",
            0L, true, "{}", null, null, 1, null, null, "{\"type\":\"auto\"}"
        )
        assertTrue(displayManager.displayMessage(giant, activity))
        looper.idle()
        assertEquals("giant", displayManager.displayingMessageId)
    }

    @Test
    fun `display into a finishing activity does not crash`() {
        val looper = shadowOf(Looper.getMainLooper())
        activity.finish()

        // Whatever the outcome, the SDK must not take the host app down.
        displayManager.displayMessage(message("msg-a", 0), activity)
        looper.idle()
    }

    @Test
    fun `configuration change re-attaches the same message to the new activity`() {
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        val newActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        displayManager.handleConfigurationChange(newActivity, true)
        looper.idle()

        assertEquals("msg-a", displayManager.displayingMessageId)
        val content = newActivity.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertTrue(
            "container must be attached to the NEW activity",
            (0 until content.childCount).any { content.getChildAt(it) is IAMWebViewContainer }
        )
    }

    @Test
    fun `configuration change with nothing displayed is a safe no-op`() {
        val newActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        displayManager.handleConfigurationChange(newActivity, true)
    }

    @Test
    fun `reattachIfNotHostedBy is a no-op when this activity already hosts the message`() {
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        displayManager.reattachIfNotHostedBy(activity)
        looper.idle()

        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    @Test
    fun `a forward navigation moves the displayed message to the activity the user is now on`() {
        // A→B startActivity, same orientation. A is stopped but NOT destroyed, so
        // the container still holds a valid window token while it sits in an
        // activity nobody can see. The re-attach used to key on that token and
        // return early: the message stayed orphaned on A with isDisplaying true —
        // nothing on screen, every later message stuck in the queue behind it,
        // and, for a campaign with dismiss-on-tap off, A's whole screen input-dead
        // when the user came back. Reproduced deterministically on a device.
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        displayManager.reattachIfNotHostedBy(second)
        looper.idle()

        assertEquals("msg-a", displayManager.displayingMessageId)
        assertTrue(
            "container must now be hosted by the activity the user is on",
            hostsContainer(second)
        )
        assertFalse(
            "container must no longer be in the activity the user left",
            hostsContainer(activity)
        )
        val webView = containerIn(second).getWebView() as android.webkit.WebView
        assertNotNull(
            "the re-attached message must still have its content loaded",
            shadowOf(webView).lastLoadDataWithBaseURL
        )
    }

    @Test
    fun `a re-attach requested off the main thread is carried out on the main thread`() {
        // Two callers reach controller.displayMessage(id, activity) from the background
        // executor. With the old window-token check that path returned early there;
        // the hosting check does not, so without an explicit hop the view tree would
        // be mutated off the main thread — CalledFromWrongThreadException on a device.
        // Robolectric does not enforce the thread, so this pins the hop itself: the
        // move must be POSTED (not done yet when the worker returns) and land on idle.
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()

        val worker = Thread { displayManager.reattachIfNotHostedBy(second) }
        worker.start()
        worker.join(5_000)

        assertFalse(
            "the move must not happen on the calling worker thread",
            hostsContainer(second)
        )
        looper.idle()
        assertTrue("the move must land once the main looper runs", hostsContainer(second))
        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    @Test
    fun `a message is not moved into an activity that is finishing`() {
        // A push-notification tap resumes the SDK's own trampoline activity, which is
        // already finishing when its resume callback fires. Detaching from the live
        // host to re-attach there fails at the isActivityGone guard AFTER the detach,
        // stranding the container with no parent: nothing on screen, queue blocked.
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()
        val finishing = Robolectric.buildActivity(Activity::class.java).setup().get()
        finishing.finish()

        displayManager.reattachIfNotHostedBy(finishing)
        looper.idle()

        assertTrue(
            "a live host must never be traded for one that cannot host",
            hostsContainer(activity)
        )
        assertEquals("msg-a", displayManager.displayingMessageId)
    }

    private fun containerIn(host: Activity): IAMWebViewContainer {
        val content = host.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (0 until content.childCount)
            .map { content.getChildAt(it) }
            .filterIsInstance<IAMWebViewContainer>()
            .single()
    }

    private fun hostsContainer(host: Activity): Boolean {
        val content = host.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (0 until content.childCount).any { content.getChildAt(it) is IAMWebViewContainer }
    }

    @Test
    fun `shutdown while a message is displayed cleans up without crashing`() {
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()

        displayManager.shutdown()

        assertNull(displayManager.displayingMessageId)
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertEquals(
            "container must be removed from the activity",
            0,
            (0 until content.childCount).count { content.getChildAt(it) is IAMWebViewContainer }
        )
    }

    private fun messageWithActions(id: String, actionsJson: String) = IAMMessage(
        id, IAMPosition.CENTER, "<html><body>hi</body></html>", 0L,
        true, actionsJson, null, null, 1, null, null, "{\"type\":\"auto\"}"
    )

    @Test
    fun `backgrounding settles the display exactly once`() {
        val looper = shadowOf(Looper.getMainLooper())
        var completions = 0
        assertTrue(displayManager.displayMessage(message("msg-a", 0), activity))
        looper.idle()
        displayManager.setMessageCompletionListener { completions++ }

        // pause() twice: a second background callback must not settle the session
        // again, or the queue would advance twice for one displayed message.
        displayManager.pause()
        displayManager.pause()
        looper.idleFor(Duration.ofMillis(500))

        assertEquals(1, completions)
        assertNull(displayManager.displayingMessageId)
    }

}
