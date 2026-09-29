package com.pushengage.pushengage.iam

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.reflect.TypeToken
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.model.IAMEnvelope
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.network.IAMJson
import com.pushengage.pushengage.iam.repository.IAMDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * End-to-end check of a **scheduled** campaign: a UTC `startDate`/`endDate` with a
 * time of day, taken from the wire, stored through Room's Date<->Long converter,
 * and filtered by [IAMDao.getValidMessages] — all while the device sits in a
 * non-UTC zone (Asia/Kolkata, +05:30).
 *
 * The window must open at the instant the UTC string names, not at the same
 * wall-clock reading in local time. Every comparison in the chain is on epoch
 * millis, so a local-time leak anywhere would shift the window by the offset —
 * exactly the "scheduled campaign never showed" symptom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMScheduledWindowTest {

    private lateinit var db: PERoomDatabase
    private lateinit var dao: IAMDao
    private var originalZone: TimeZone? = null

    // 2026-08-20T12:30:00Z == 18:00 IST, 2026-08-20T16:30:00Z == 22:00 IST
    private val startUtcMillis = 1787229000000L
    private val endUtcMillis = 1787243400000L
    private val fiveAndAHalfHours = 19_800_000L

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PERoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.iamDao()
    }

    @After
    fun tearDown() {
        db.close()
        originalZone?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun `utc time-of-day window lands on the right instant on an IST device`() {
        val message = parseSingle(
            startDate = "\"2026-08-20T12:30:00Z\"",
            endDate = "\"2026-08-20T16:30:00Z\""
        )

        assertEquals("start parsed as UTC, not local", startUtcMillis, message.startDate?.time)
        assertEquals("end parsed as UTC, not local", endUtcMillis, message.endDate?.time)
    }

    @Test
    fun `window filter opens and closes at the utc instants, not the local reading`() {
        dao.insertMessages(
            listOf(
                parseSingle(
                    startDate = "\"2026-08-20T12:30:00Z\"",
                    endDate = "\"2026-08-20T16:30:00Z\""
                ).let(::toEntity)
            )
        )

        assertTrue(
            "one second before the start the campaign must be withheld",
            dao.getValidMessages(startUtcMillis - 1000L).isEmpty()
        )
        assertEquals(
            "at the start instant (18:00 IST) the campaign must be eligible",
            1, dao.getValidMessages(startUtcMillis).size
        )
        assertEquals(
            "mid-window", 1, dao.getValidMessages(startUtcMillis + 60_000L).size
        )
        assertEquals(
            "at the end instant it is still inside the window",
            1, dao.getValidMessages(endUtcMillis).size
        )
        assertTrue(
            "one second past the end the campaign must be withheld",
            dao.getValidMessages(endUtcMillis + 1000L).isEmpty()
        )
        // The failure this guards: reading "12:30:00" as local wall-clock time would
        // place the opening at 12:30 IST (07:00Z) — 5h30m EARLY on this device, and
        // symmetrically 5h30m late for a zone west of UTC. Neither instant may open
        // the window.
        assertTrue(
            "the window must not open at the local-wall-clock misreading",
            dao.getValidMessages(startUtcMillis - fiveAndAHalfHours).isEmpty()
        )
    }

    @Test
    fun `millisecond precision utc dates are accepted`() {
        val message = parseSingle(
            startDate = "\"2026-08-20T12:30:00.000Z\"",
            endDate = "\"2026-08-20T16:30:00.000Z\""
        )
        assertEquals(startUtcMillis, message.startDate?.time)
        assertEquals(endUtcMillis, message.endDate?.time)
    }

    /**
     * Characterisation, not an endorsement: the date adapter accepts only
     * `...ssZ` and `...ss.SSSZ`. Any other ISO-8601 UTC spelling — notably the
     * `+00:00` offset form — throws, and because campaigns arrive as one array the
     * throw takes the **whole response** down: every campaign on the site
     * disappears, not just the scheduled one, and the sync leaves the stale local
     * rows in place.
     */
    @Test
    fun `one unparseable date rejects the entire campaign batch`() {
        val json = """
            { "error_code": 0, "data": [
                ${campaignJson("plain", "null", "null")},
                ${campaignJson("scheduled", "\"2026-08-20T12:30:00+00:00\"", "null")},
                ${campaignJson("other", "null", "null")}
            ] }
        """.trimIndent()

        val type = object : TypeToken<IAMEnvelope<List<IAMMessageResponse>>>() {}.type
        assertThrows(com.google.gson.JsonParseException::class.java) {
            IAMJson.gson.fromJson<IAMEnvelope<List<IAMMessageResponse>>>(json, type)
        }
    }

    private fun parseSingle(startDate: String, endDate: String): IAMMessageResponse =
        IAMJson.gson.fromJson(
            campaignJson("sched_1", startDate, endDate),
            IAMMessageResponse::class.java
        )

    private fun toEntity(response: IAMMessageResponse) = IAMMessage(
        response.id,
        response.position,
        response.htmlContent,
        response.displayDuration,
        response.shouldDismissOnTap,
        IAMJson.gson.toJson(response.actions),
        response.startDate,
        response.endDate,
        response.priority,
        null,
        IAMJson.gson.toJson(response.frequency),
        IAMJson.gson.toJson(response.trigger)
    )

    private fun campaignJson(id: String, startDate: String, endDate: String) = """
        {
          "id": "$id",
          "position": "top",
          "htmlContent": "<html><body>hi</body></html>",
          "displayDuration": 0,
          "shouldDismissOnTap": false,
          "priority": 2,
          "startDate": $startDate,
          "endDate": $endDate,
          "trigger": { "type": "custom", "event": "sched" },
          "frequency": { "type": "one_time" },
          "actions": {}
        }
    """.trimIndent()
}
