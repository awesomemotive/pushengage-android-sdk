package com.pushengage.pushengage.iam.queue

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards the delay contract: app-open campaigns wait out `trigger.delay`, the
 * countdown freezes while backgrounded, and it resumes with the remaining time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IAMTriggerDelaySchedulerTest {

    private lateinit var released: MutableList<String>
    private var now: Long = 0L

    @Before
    fun setUp() {
        released = mutableListOf()
        now = 10_000L
    }

    private fun scheduler() = IAMTriggerDelayScheduler(
        onDue = { released.add(it) },
        clock = { now },
        handler = Handler(Looper.getMainLooper())
    )

    /** Advances both the injected clock and the Robolectric looper together. */
    private fun advance(millis: Long) {
        now += millis
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
    }

    @Test
    fun `a non-positive delay releases immediately`() {
        val s = scheduler()
        s.schedule("m1", 0L)
        s.schedule("m2", -5L)

        assertEquals(listOf("m1", "m2"), released)
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun `a delayed message is withheld until the delay elapses`() {
        val s = scheduler()
        s.schedule("m1", 5_000L)

        assertTrue("must not release before the delay", released.isEmpty())
        assertEquals(1, s.pendingCount())

        advance(4_999L)
        assertTrue("still inside the window", released.isEmpty())

        advance(1L)
        assertEquals(listOf("m1"), released)
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun `backgrounding freezes the countdown and time in background does not count`() {
        val s = scheduler()
        s.schedule("m1", 5_000L)

        advance(2_000L)          // 3s left
        s.pause()

        // A long stretch in the background must not release the message, and must
        // not burn any of the remaining 3s.
        advance(60_000L)
        assertTrue("background time must not release the message", released.isEmpty())
        assertEquals(1, s.pendingCount())

        s.resume()
        advance(2_999L)
        assertTrue("2.999s of the remaining 3s — not yet due", released.isEmpty())

        advance(1L)
        assertEquals(listOf("m1"), released)
    }

    @Test
    fun `resume restarts from the remaining time, not the full delay`() {
        val s = scheduler()
        s.schedule("m1", 10_000L)

        advance(9_000L)          // 1s left
        s.pause()
        advance(5_000L)
        s.resume()

        advance(1_000L)          // exactly the 1s that was left
        assertEquals("must not restart the full 10s", listOf("m1"), released)
    }

    @Test
    fun `scheduling while paused does not start the countdown until resume`() {
        val s = scheduler()
        s.pause()
        s.schedule("m1", 1_000L)

        advance(5_000L)
        assertTrue("paused scheduler must not release", released.isEmpty())

        s.resume()
        advance(1_000L)
        assertEquals(listOf("m1"), released)
    }

    @Test
    fun `re-scheduling an already pending message is ignored`() {
        val s = scheduler()
        s.schedule("m1", 3_000L)
        s.schedule("m1", 3_000L)

        assertEquals(1, s.pendingCount())
        advance(3_000L)
        assertEquals("must release once, not twice", listOf("m1"), released)
    }

    @Test
    fun `clear drops pending countdowns without releasing them`() {
        val s = scheduler()
        s.schedule("m1", 2_000L)
        s.clear()

        advance(5_000L)
        assertTrue("cleared messages must never be released", released.isEmpty())
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun `independent messages release on their own schedules`() {
        val s = scheduler()
        s.schedule("slow", 5_000L)
        s.schedule("fast", 1_000L)

        advance(1_000L)
        assertEquals(listOf("fast"), released)

        advance(4_000L)
        assertEquals(listOf("fast", "slow"), released)
    }

    @Test
    fun `repeated pause and resume are idempotent`() {
        val s = scheduler()
        s.schedule("m1", 4_000L)

        advance(1_000L)          // 3s left
        s.pause()
        s.pause()               // second pause must not re-bank elapsed time
        advance(10_000L)
        s.resume()
        s.resume()              // second resume must not double-post

        advance(3_000L)
        assertEquals(listOf("m1"), released)
    }
}
