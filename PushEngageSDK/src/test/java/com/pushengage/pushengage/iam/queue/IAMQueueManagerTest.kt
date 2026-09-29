package com.pushengage.pushengage.iam.queue

import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [IAMQueueManager]: priority ordering, dedup, one-at-a-time
 * delivery, pause/resume, and listener semantics. No Android dependencies —
 * [PELogger] is kept disabled so android.util.Log is never touched.
 */
class IAMQueueManagerTest {

    private lateinit var queueManager: IAMQueueManager
    private lateinit var listener: RecordingListener

    @Before
    fun setUp() {
        // Other test classes in the same JVM may leave SDK logging enabled;
        // disable it so PELogger never calls the unmocked android.util.Log.
        PELogger.enableLogging(false)
        queueManager = IAMQueueManager()
        listener = RecordingListener()
    }

    // ---- basic delivery ----

    @Test
    fun addMessages_deliversFirstMessageToListener() {
        queueManager.addMessageListener(listener)

        val added = queueManager.addMessages(listOf(message("m1", priority = 1)))

        assertEquals(1, added)
        assertEquals(listOf("m1"), listener.receivedIds())
        assertEquals("m1", queueManager.getCurrentMessage()?.id)
        assertEquals("delivered message leaves the pending queue", 0, queueManager.getQueueSize())
    }

    @Test
    fun getCurrentMessage_initiallyNull() {
        assertNull(queueManager.getCurrentMessage())
    }

    @Test
    fun addMessages_emptyList_addsNothingAndNotifiesNothing() {
        queueManager.addMessageListener(listener)

        assertEquals(0, queueManager.addMessages(emptyList()))
        assertTrue(listener.events.isEmpty())
        assertNull(queueManager.getCurrentMessage())
        assertEquals(0, queueManager.getQueueSize())
    }

    // ---- priority ordering ----

    @Test
    fun pausedBatch_lowestPriorityNumberSurfacesFirst_regardlessOfInsertionOrder() {
        queueManager.addMessageListener(listener)
        queueManager.pause()

        // Deliberately insert highest priority number first.
        queueManager.addMessages(
            listOf(
                message("low", priority = 9),
                message("mid", priority = 5),
                message("high", priority = 1)
            )
        )
        queueManager.resume()

        assertEquals("high", queueManager.getCurrentMessage()?.id)
        assertEquals(listOf("high"), listener.receivedIds())
    }

    @Test
    fun markCurrentMessageComplete_advancesThroughQueueInPriorityOrder() {
        queueManager.addMessageListener(listener)
        queueManager.pause()
        queueManager.addMessages(
            listOf(
                message("p3", priority = 3),
                message("p1", priority = 1),
                message("p2", priority = 2)
            )
        )
        queueManager.resume()

        assertEquals("p1", queueManager.getCurrentMessage()?.id)
        queueManager.markCurrentMessageComplete()
        assertEquals("p2", queueManager.getCurrentMessage()?.id)
        queueManager.markCurrentMessageComplete()
        assertEquals("p3", queueManager.getCurrentMessage()?.id)

        // Non-null deliveries observed by the listener, in order.
        assertEquals(listOf("p1", "p2", "p3"), listener.receivedIds())
    }

    @Test
    fun unpausedBatchAdd_deliversLowestPriorityNumberFirst_acrossTheWholeBatch() {
        // The batch is enqueued in full and processed once, so the most urgent
        // message (lowest priority number) is delivered first regardless of
        // insertion order — even when the queue is not paused.
        queueManager.addMessageListener(listener)

        queueManager.addMessages(
            listOf(
                message("low", priority = 9),
                message("high", priority = 1)
            )
        )

        assertEquals("high", queueManager.getCurrentMessage()?.id)
        queueManager.markCurrentMessageComplete()
        assertEquals("low", queueManager.getCurrentMessage()?.id)
        assertEquals(listOf("high", "low"), listener.receivedIds())
    }

    // ---- dedup ----

    @Test
    fun addMessages_sameIdTwice_whileQueued_isOnlyQueuedOnce() {
        queueManager.pause()

        assertEquals(1, queueManager.addMessages(listOf(message("m1", priority = 1))))
        assertEquals(0, queueManager.addMessages(listOf(message("m1", priority = 1))))
        assertEquals(1, queueManager.getQueueSize())
    }

    @Test
    fun addMessages_duplicateIdsInSameBatch_onlyFirstIsAdded() {
        queueManager.pause()

        val added = queueManager.addMessages(
            listOf(
                message("m1", priority = 1),
                message("m1", priority = 7) // same id, different payload
            )
        )

        assertEquals(1, added)
        assertEquals(1, queueManager.getQueueSize())
    }

    @Test
    fun addMessages_sameIdAsCurrentlyDisplayedMessage_isRejected() {
        // Dedup covers the currently displayed message, not just the pending
        // queue — a campaign already on screen cannot be re-queued, which would
        // otherwise cause a back-to-back duplicate display.
        queueManager.addMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        assertEquals("m1", queueManager.getCurrentMessage()?.id)

        val addedAgain = queueManager.addMessages(listOf(message("m1", priority = 1)))

        assertEquals(0, addedAgain)
        assertEquals(0, queueManager.getQueueSize())
    }

    // ---- one-at-a-time ----

    @Test
    fun secondMessage_notDeliveredUntilCurrentMessageComplete() {
        queueManager.addMessageListener(listener)

        queueManager.addMessages(listOf(message("m1", priority = 1)))
        queueManager.addMessages(listOf(message("m2", priority = 2)))

        assertEquals("only the first message is delivered", listOf("m1"), listener.receivedIds())
        assertEquals("m1", queueManager.getCurrentMessage()?.id)
        assertEquals(1, queueManager.getQueueSize())

        queueManager.markCurrentMessageComplete()

        assertEquals(listOf("m1", "m2"), listener.receivedIds())
        assertEquals("m2", queueManager.getCurrentMessage()?.id)
    }

    @Test
    fun markCurrentMessageComplete_notifiesNullBetweenMessages() {
        queueManager.addMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        queueManager.addMessages(listOf(message("m2", priority = 2)))

        queueManager.markCurrentMessageComplete()

        // Full event stream: m1, then null (message dismissed), then m2.
        assertEquals(listOf("m1", null, "m2"), listener.events.map { it?.id })
    }

    // ---- pause / resume ----

    @Test
    fun pause_messagesQueueButListenerIsNotNotified() {
        queueManager.addMessageListener(listener)
        queueManager.pause()

        queueManager.addMessages(listOf(message("m1", priority = 1)))

        assertTrue(listener.events.isEmpty())
        assertNull(queueManager.getCurrentMessage())
        assertEquals(1, queueManager.getQueueSize())
    }

    @Test
    fun resume_deliversQueuedMessage() {
        queueManager.addMessageListener(listener)
        queueManager.pause()
        queueManager.addMessages(listOf(message("m1", priority = 1)))

        queueManager.resume()

        assertEquals(listOf("m1"), listener.receivedIds())
        assertEquals("m1", queueManager.getCurrentMessage()?.id)
        assertEquals(0, queueManager.getQueueSize())
    }

    @Test
    fun resume_withEmptyQueue_doesNothing() {
        queueManager.addMessageListener(listener)
        queueManager.pause()
        queueManager.resume()

        assertTrue(listener.events.isEmpty())
        assertNull(queueManager.getCurrentMessage())
    }

    @Test
    fun completeWhilePaused_clearsCurrentButDoesNotAdvance_untilResume() {
        queueManager.addMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        queueManager.addMessages(listOf(message("m2", priority = 2)))

        queueManager.pause()
        queueManager.markCurrentMessageComplete()

        assertNull(queueManager.getCurrentMessage())
        assertEquals("m2 must not be delivered while paused", listOf("m1"), listener.receivedIds())

        queueManager.resume()

        assertEquals("m2", queueManager.getCurrentMessage()?.id)
        assertEquals(listOf("m1", "m2"), listener.receivedIds())
    }

    @Test
    fun pause_doesNotAffectCurrentlyDisplayedMessage() {
        queueManager.addMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))

        queueManager.pause()

        assertEquals("m1", queueManager.getCurrentMessage()?.id)
    }

    // ---- listener semantics ----

    @Test
    fun addMessageListener_whileMessageIsCurrent_notifiesImmediately() {
        queueManager.addMessages(listOf(message("m1", priority = 1)))

        queueManager.addMessageListener(listener)

        assertEquals(listOf("m1"), listener.receivedIds())
    }

    @Test
    fun addMessageListener_sameInstanceTwice_registersOnce() {
        queueManager.addMessageListener(listener)
        queueManager.addMessageListener(listener)

        queueManager.addMessages(listOf(message("m1", priority = 1)))

        assertEquals("duplicate registration must not double-notify", 1, listener.events.size)
    }

    @Test
    fun removeMessageListener_stopsFurtherNotifications() {
        queueManager.addMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        assertEquals(1, listener.events.size)

        queueManager.removeMessageListener(listener)
        queueManager.markCurrentMessageComplete()
        queueManager.addMessages(listOf(message("m2", priority = 2)))

        assertEquals("removed listener must receive no further events", 1, listener.events.size)
    }

    @Test
    fun removeMessageListener_neverRegistered_doesNotCrash() {
        queueManager.removeMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun multipleListeners_allReceiveTheSameMessageInstance() {
        val second = RecordingListener()
        queueManager.addMessageListener(listener)
        queueManager.addMessageListener(second)

        val msg = message("m1", priority = 1)
        queueManager.addMessages(listOf(msg))

        assertSame(msg, listener.events.single())
        assertSame(msg, second.events.single())
    }

    @Test
    fun throwingListener_doesNotPreventOtherListenersFromBeingNotified() {
        val throwing = object : IAMMessageListener {
            override fun onMessageAvailable(message: IAMMessage?) {
                throw IllegalStateException("listener failure")
            }
        }
        queueManager.addMessageListener(throwing)
        queueManager.addMessageListener(listener)

        queueManager.addMessages(listOf(message("m1", priority = 1)))

        assertEquals(listOf("m1"), listener.receivedIds())
    }

    // ---- empty-queue behaviors ----

    @Test
    fun markCurrentMessageComplete_withNothingCurrent_doesNotCrashAndStaysIdle() {
        queueManager.markCurrentMessageComplete()

        assertNull(queueManager.getCurrentMessage())
        assertEquals(0, queueManager.getQueueSize())
    }

    @Test
    fun markCurrentMessageComplete_withNothingCurrent_doesNotBroadcast() {
        // Completing with nothing displayed is silent — no spurious "no message
        // available" event is sent to listeners.
        queueManager.addMessageListener(listener)

        queueManager.markCurrentMessageComplete()

        assertTrue(listener.events.isEmpty())
    }

    // ---- queue maintenance ----

    @Test
    fun clearQueue_removesPendingMessagesButKeepsCurrent() {
        queueManager.addMessageListener(listener)
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        queueManager.addMessages(listOf(message("m2", priority = 2), message("m3", priority = 3)))
        assertEquals(2, queueManager.getQueueSize())

        queueManager.clearQueue()

        assertEquals(0, queueManager.getQueueSize())
        assertEquals("current message is not affected by clearQueue", "m1", queueManager.getCurrentMessage()?.id)

        queueManager.markCurrentMessageComplete()
        assertNull("nothing left to deliver after clearQueue", queueManager.getCurrentMessage())
    }

    @Test
    fun removeMessage_pendingMessage_returnsTrueAndShrinksQueue() {
        queueManager.pause()
        queueManager.addMessages(listOf(message("m1", priority = 1), message("m2", priority = 2)))

        assertTrue(queueManager.removeMessage("m2"))
        assertEquals(1, queueManager.getQueueSize())
        assertFalse("already removed", queueManager.removeMessage("m2"))
    }

    @Test
    fun removeMessage_unknownId_returnsFalse() {
        assertFalse(queueManager.removeMessage("nope"))
    }

    @Test
    fun removeMessage_isScopedToPendingQueue_notTheDisplayedMessage() {
        // By design removeMessage only operates on the pending queue. Dismissing
        // an on-screen message is the display manager's responsibility; removing
        // it here would desync the visible view from the queue state.
        queueManager.addMessages(listOf(message("m1", priority = 1)))
        assertEquals("m1", queueManager.getCurrentMessage()?.id)

        assertFalse(queueManager.removeMessage("m1"))
        assertEquals("m1", queueManager.getCurrentMessage()?.id)
    }

    @Test
    fun getQueueSize_reflectsPendingMessagesOnly() {
        assertEquals(0, queueManager.getQueueSize())

        queueManager.addMessages(listOf(message("m1", priority = 1))) // becomes current
        assertEquals(0, queueManager.getQueueSize())

        queueManager.addMessages(listOf(message("m2", priority = 2)))
        assertEquals(1, queueManager.getQueueSize())
    }

    // ---- helpers ----

    private fun message(id: String, priority: Int): IAMMessage = IAMMessage(
        id = id,
        position = IAMPosition.CENTER,
        htmlContent = "<html><body>$id</body></html>",
        displayDuration = 0L,
        shouldDismissOnTap = false,
        actionsJson = "{}",
        startDate = null,
        endDate = null,
        priority = priority,
        audienceJson = null,
        frequencyJson = null,
        triggerJson = """{"type":"custom","event":"evt_$id"}"""
    )

    private class RecordingListener : IAMMessageListener {
        val events = mutableListOf<IAMMessage?>()

        override fun onMessageAvailable(message: IAMMessage?) {
            events.add(message)
        }

        /** Ids of non-null deliveries, in order. */
        fun receivedIds(): List<String> = events.mapNotNull { it?.id }
    }
}
