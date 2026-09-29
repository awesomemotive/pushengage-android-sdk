package com.pushengage.pushengage.iam.queue

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMMessage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pausable countdown for app-open campaigns that declare `trigger.delay`.
 *
 * - **App open only.** Custom (`on event`) triggers ignore `delay`; this scheduler
 *   is only fed from the auto-trigger path.
 * - **Backgrounding freezes the countdown** and returning resumes it with the time
 *   that was left — background time never counts toward the delay. A 5 s delay
 *   backgrounded at 2 s still has 3 s to run when the app comes back.
 * - **Origin is app open, gated on the sync.** Callers pass the *remaining* delay,
 *   computed from the app-open instant, so the effective display time is
 *   `max(appOpen + delay, syncComplete)`.
 *
 * Deliberately in-memory only: app open fires once per process, so if the process
 * dies the next launch re-fires app open and the delay starts over.
 *
 * Countdowns are keyed by campaign id and hold no campaign copy: a sync
 * full-replaces the campaign set while one waits, so the campaign can be deleted,
 * paused or expire mid-countdown. [onDue] re-reads it and re-checks eligibility.
 */
internal class IAMTriggerDelayScheduler @JvmOverloads constructor(
    private val onDue: (String) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    private class Pending(
        val messageId: String,
        var remainingMillis: Long,
        var startedAt: Long,
        var runnable: Runnable? = null
    )

    private val lock = ReentrantLock()
    private val pending = LinkedHashMap<String, Pending>()
    private var paused = false

    /**
     * Schedules the campaign [messageId] to be released after [delayMillis] of
     * foreground time. A non-positive delay releases it immediately. Re-scheduling an
     * id that is already waiting is ignored, so a repeated evaluation cannot
     * double-queue it.
     */
    fun schedule(messageId: String, delayMillis: Long) {
        if (delayMillis <= 0) {
            onDue(messageId)
            return
        }

        val alreadyWaiting = lock.withLock {
            if (pending.containsKey(messageId)) {
                true
            } else {
                pending[messageId] = Pending(messageId, delayMillis, clock())
                if (!paused) startLocked(messageId)
                false
            }
        }

        if (alreadyWaiting) {
            PELogger.debug("IAM delay: message $messageId already waiting — ignoring re-schedule")
        } else {
            PELogger.debug("IAM delay: message $messageId scheduled in ${delayMillis}ms" +
                    if (paused) " (paused — will start on resume)" else "")
        }
    }

    /** Freezes every countdown, banking the time already served. */
    fun pause() {
        lock.withLock {
            if (paused) return
            paused = true
            val now = clock()
            pending.values.forEach { entry ->
                entry.runnable?.let { handler.removeCallbacks(it) }
                entry.runnable = null
                val served = now - entry.startedAt
                entry.remainingMillis = (entry.remainingMillis - served).coerceAtLeast(0L)
            }
            if (pending.isNotEmpty()) {
                PELogger.debug("IAM delay: paused ${pending.size} pending countdown(s)")
            }
        }
    }

    /** Restarts every countdown from the time that was left when [pause] ran. */
    fun resume() {
        lock.withLock {
            if (!paused) return
            paused = false
            pending.keys.toList().forEach { startLocked(it) }
            if (pending.isNotEmpty()) {
                PELogger.debug("IAM delay: resumed ${pending.size} pending countdown(s)")
            }
        }
    }

    /** Drops every pending countdown without releasing the messages. */
    fun clear() {
        lock.withLock {
            pending.values.forEach { entry ->
                entry.runnable?.let { handler.removeCallbacks(it) }
            }
            pending.clear()
        }
    }

    /** Number of countdowns still waiting. Test/diagnostic aid. */
    fun pendingCount(): Int = lock.withLock { pending.size }

    private fun startLocked(messageId: String) {
        val entry = pending[messageId] ?: return
        entry.startedAt = clock()
        val runnable = Runnable { release(messageId) }
        entry.runnable = runnable
        handler.postDelayed(runnable, entry.remainingMillis)
    }

    /**
     * Releases a due message. The [onDue] callback runs *outside* the lock — it
     * enqueues into the queue manager, which takes its own lock.
     */
    private fun release(messageId: String) {
        lock.withLock { pending.remove(messageId) } ?: return
        PELogger.debug("IAM delay: message $messageId delay elapsed — releasing to queue")
        onDue(messageId)
    }
}
