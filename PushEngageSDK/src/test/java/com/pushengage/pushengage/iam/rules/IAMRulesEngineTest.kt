package com.pushengage.pushengage.iam.rules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.iam.model.IAMFrequency
import com.pushengage.pushengage.iam.model.IAMFrequencyType
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.network.IAMJson
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.iam.util.IAMScalarString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * JVM unit tests for [IAMRulesEngine]: date-window validity, frequency
 * capping (wire values "one_time"/"recurring"/"capped" per the backend
 * contract, plus legacy enum names and the iOS "one-time" alternate parsed
 * via [IAMJson]), and audience-condition evaluation.
 *
 * [IAMRepository] is mocked; its Mockito defaults (getDisplayCount = 0,
 * getLastDisplayTimestamp = 0) represent "never displayed".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMRulesEngineTest {

    private lateinit var context: Context
    private lateinit var repository: IAMRepository
    private lateinit var engine: IAMRulesEngine

    private val hourMillis = 60 * 60 * 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // IAMDevicePropertiesManager is a process-wide singleton; reset it so
        // each test binds a fresh instance to this test's application context.
        val instanceField = IAMDevicePropertiesManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
        context.getSharedPreferences("pe_iam_user_attributes", Context.MODE_PRIVATE)
            .edit().clear().commit()

        repository = mock()
        engine = IAMRulesEngine(context, repository)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun message(
        id: String = "msg-1",
        startDate: Date? = null,
        endDate: Date? = null,
        frequencyJson: String? = null,
        audienceJson: String? = null
    ): IAMMessage = IAMMessage(
        id = id,
        position = IAMPosition.CENTER,
        htmlContent = "<html><body>hi</body></html>",
        displayDuration = 0L,
        shouldDismissOnTap = false,
        actionsJson = "{}",
        startDate = startDate,
        endDate = endDate,
        priority = 1,
        audienceJson = audienceJson,
        frequencyJson = frequencyJson,
        triggerJson = """{"type":"custom","event":"test_event"}"""
    )

    /** Wire-format frequency JSON exactly as Room stores it (via Gson + @SerializedName). */
    private fun wireFrequencyJson(type: IAMFrequencyType, count: Int? = null, interval: Long? = null): String =
        IAMJson.gson.toJson(IAMFrequency(type, count, interval))

    private fun stubDisplayCount(id: String, count: Int) {
        whenever(repository.getDisplayCount(id)).thenReturn(count)
    }

    private fun stubLastDisplay(id: String, timestampMillis: Long) {
        whenever(repository.getLastDisplayTimestamp(id)).thenReturn(timestampMillis)
    }

    // ---------------------------------------------------------------------
    // 1. Date-window validity
    // ---------------------------------------------------------------------

    @Test
    fun `message within date window is eligible`() {
        val now = System.currentTimeMillis()
        val msg = message(startDate = Date(now - hourMillis), endDate = Date(now + hourMillis))

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `message before start date is not eligible`() {
        val now = System.currentTimeMillis()
        val msg = message(startDate = Date(now + hourMillis), endDate = Date(now + 2 * hourMillis))

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `message after end date is not eligible`() {
        val now = System.currentTimeMillis()
        val msg = message(startDate = Date(now - 2 * hourMillis), endDate = Date(now - hourMillis))

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `null start date is unbounded at the start`() {
        val now = System.currentTimeMillis()
        val msg = message(startDate = null, endDate = Date(now + hourMillis))

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `null end date is unbounded at the end`() {
        val now = System.currentTimeMillis()
        val msg = message(startDate = Date(now - hourMillis), endDate = null)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `both dates null means always within window`() {
        assertTrue(engine.isEligibleForDisplay(message(startDate = null, endDate = null)))
    }

    // ---------------------------------------------------------------------
    // 2. Frequency capping — ONE_TIME
    // ---------------------------------------------------------------------

    @Test
    fun `one_time wire value never displayed is eligible`() {
        val msg = message(frequencyJson = wireFrequencyJson(IAMFrequencyType.ONE_TIME))
        stubDisplayCount(msg.id, 0)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `one_time wire value displayed once is not eligible`() {
        val msg = message(frequencyJson = """{"type":"one_time"}""")
        stubDisplayCount(msg.id, 1)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `legacy ONE_TIME enum name never displayed is eligible`() {
        val msg = message(frequencyJson = """{"type":"ONE_TIME"}""")
        stubDisplayCount(msg.id, 0)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `legacy ONE_TIME enum name displayed once is not eligible`() {
        // Regression guard: raw string comparison against the enum name used to
        // work here but silently disabled capping for wire-format JSON.
        val msg = message(frequencyJson = """{"type":"ONE_TIME"}""")
        stubDisplayCount(msg.id, 1)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `iOS one-time hyphenated value displayed once is not eligible`() {
        val msg = message(frequencyJson = """{"type":"one-time"}""")
        stubDisplayCount(msg.id, 1)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 2. Frequency capping — CAPPED
    // ---------------------------------------------------------------------

    @Test
    fun `capped count 3 with zero displays is eligible`() {
        val msg = message(frequencyJson = wireFrequencyJson(IAMFrequencyType.CAPPED, count = 3))
        stubDisplayCount(msg.id, 0)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped count 3 with two displays is eligible`() {
        val msg = message(frequencyJson = """{"type":"capped","count":3}""")
        stubDisplayCount(msg.id, 2)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped count 3 with three displays is not eligible`() {
        // Regression guard: the wire value "capped" must be recognized so the
        // cap actually applies (raw name comparison used to skip it).
        val msg = message(frequencyJson = """{"type":"capped","count":3}""")
        stubDisplayCount(msg.id, 3)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped count 3 with four displays is not eligible`() {
        val msg = message(frequencyJson = """{"type":"capped","count":3}""")
        stubDisplayCount(msg.id, 4)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `legacy CAPPED enum name with cap reached is not eligible`() {
        val msg = message(frequencyJson = """{"type":"CAPPED","count":3}""")
        stubDisplayCount(msg.id, 3)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped without a count fails closed`() {
        // Previously this fell back to Int.MAX_VALUE, so a campaign that explicitly
        // asked to be capped became UNLIMITED — failing open in the one place the
        // author had asked for a limit.
        val msg = message(frequencyJson = """{"type":"capped"}""")
        stubDisplayCount(msg.id, 1000)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped without a count fails closed even with no prior displays`() {
        val msg = message(frequencyJson = """{"type":"capped"}""")
        stubDisplayCount(msg.id, 0)

        assertFalse("an unenforceable cap must not be treated as no cap", engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `an explicit zero interval is legal and means no spacing`() {
        // Distinct from an absent interval: 0 is a deliberate "no spacing" value, and
        // the bundled test content relies on it.
        val recurring = message(frequencyJson = """{"type":"recurring","interval":0}""")
        stubLastDisplay(recurring.id, System.currentTimeMillis() - 1_000L)
        assertTrue(engine.isEligibleForDisplay(recurring))

        val capped = message(frequencyJson = """{"type":"capped","count":3,"interval":0}""")
        stubDisplayCount(capped.id, 1)
        stubLastDisplay(capped.id, System.currentTimeMillis() - 1_000L)
        assertTrue(engine.isEligibleForDisplay(capped))
    }

    // --- CAPPED also honors the minimum spacing ("at most once every X") ---

    @Test
    fun `capped within count but inside the interval is not eligible`() {
        // "Up to 3 times, at most once every 24h": under the cap, but the last
        // display was an hour ago. Enforcing only the count would let all three
        // displays fire back to back.
        val msg = message(frequencyJson = """{"type":"capped","count":3,"interval":86400}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 3_600_000L)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped within count and past the interval is eligible`() {
        val msg = message(frequencyJson = """{"type":"capped","count":3,"interval":86400}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 90_000_000L) // > 24h

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped exactly at the interval boundary is eligible`() {
        val msg = message(frequencyJson = """{"type":"capped","count":3,"interval":60}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 60_000L)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped at the count limit stays ineligible even past the interval`() {
        // The cap wins: spacing satisfied, but there are no displays left.
        val msg = message(frequencyJson = """{"type":"capped","count":3,"interval":60}""")
        stubDisplayCount(msg.id, 3)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 90_000_000L)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped with no interval is a pure count cap`() {
        // Backward compatibility: campaigns published without an interval keep
        // behaving exactly as before — count only, no spacing.
        val msg = message(frequencyJson = """{"type":"capped","count":3}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 1_000L) // 1s ago

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped with zero interval is a pure count cap`() {
        val msg = message(frequencyJson = """{"type":"capped","count":3,"interval":0}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 1_000L)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `capped with an interval but never displayed is eligible`() {
        val msg = message(frequencyJson = """{"type":"capped","count":3,"interval":86400}""")
        stubDisplayCount(msg.id, 0)
        stubLastDisplay(msg.id, 0L)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `recurring ignores count so it is not a total cap`() {
        // A recurring campaign has no total cap; `count` is not read. Anything
        // needing one must use `capped` instead.
        val msg = message(frequencyJson = """{"type":"recurring","count":2,"interval":60}""")
        stubDisplayCount(msg.id, 500)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 90_000L) // past interval

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 2. Frequency capping — RECURRING
    // ---------------------------------------------------------------------

    @Test
    fun `recurring interval 10s with last display 5s ago is not eligible`() {
        val msg = message(frequencyJson = wireFrequencyJson(IAMFrequencyType.RECURRING, interval = 10L))
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 5_000L)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `recurring interval 10s with last display 15s ago is eligible`() {
        val msg = message(frequencyJson = """{"type":"recurring","interval":10}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 15_000L)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `legacy RECURRING enum name with last display 5s ago is not eligible`() {
        // Regression guard for the wire-value standardization: legacy stored
        // JSON must keep throttling too.
        val msg = message(frequencyJson = """{"type":"RECURRING","interval":10}""")
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, System.currentTimeMillis() - 5_000L)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `recurring interval 0 is always eligible`() {
        val msg = message(frequencyJson = """{"type":"recurring","interval":0}""")
        stubDisplayCount(msg.id, 5)
        stubLastDisplay(msg.id, System.currentTimeMillis())

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `recurring with missing interval fails closed`() {
        // `interval` is required for recurring. It used to default to 0 ("no spacing"),
        // so a malformed rule became unlimited — fail-open. An explicit 0 is still
        // legal; see `an explicit zero interval is legal and means no spacing`.
        val msg = message(frequencyJson = """{"type":"recurring"}""")
        stubDisplayCount(msg.id, 5)
        stubLastDisplay(msg.id, System.currentTimeMillis())

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `recurring with no previous display is eligible`() {
        val msg = message(frequencyJson = """{"type":"recurring","interval":86400}""")
        stubDisplayCount(msg.id, 0)
        stubLastDisplay(msg.id, 0L)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 2. Frequency capping — malformed / missing / unknown
    // ---------------------------------------------------------------------

    @Test
    fun `malformed frequency json fails closed`() {
        // An unparseable frequency rule fails closed (not eligible), consistent
        // with malformed audience handling — a rule we can't evaluate must not
        // risk over-displaying.
        val msg = message(frequencyJson = "{not valid json!")
        stubDisplayCount(msg.id, 99)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `empty frequency json defaults to eligible`() {
        val msg = message(frequencyJson = "")
        stubDisplayCount(msg.id, 99)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `null frequency json defaults to eligible`() {
        val msg = message(frequencyJson = null)
        stubDisplayCount(msg.id, 99)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `unknown frequency type defaults to eligible`() {
        // Gson maps an unknown enum wire value to null, hitting the else branch.
        val msg = message(frequencyJson = """{"type":"weekly_digest"}""")
        stubDisplayCount(msg.id, 99)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 3. Audience conditions — eq / neq
    // ---------------------------------------------------------------------

    @Test
    fun `eq operator passes when attribute matches`() {
        engine.setUserAttribute("plan", "premium")
        val msg = message(audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `eq operator fails when attribute differs`() {
        engine.setUserAttribute("plan", "free")
        val msg = message(audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `eq operator matches built-in platform attribute`() {
        val msg = message(audienceJson = """[{"field":"platform","op":"eq","value":"Android"}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `eq operator accepts the contract array value form`() {
        // The backend sends single-value operators as a one-element
        // array; the engine must unwrap it, not compare against "[premium]".
        engine.setUserAttribute("plan", "premium")
        val msg = message(audienceJson = """[{"field":"plan","op":"eq","value":["premium"]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `contains operator accepts the contract array value form`() {
        engine.setUserAttribute("email", "user@example.com")
        val msg = message(audienceJson = """[{"field":"email","op":"contains","value":["@example.com"]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `gt operator accepts the contract array value form`() {
        engine.setUserAttribute("session_count", "10")
        val msg = message(audienceJson = """[{"field":"session_count","op":"gt","value":[5]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `neq operator passes when attribute differs`() {
        engine.setUserAttribute("plan", "free")
        val msg = message(audienceJson = """[{"field":"plan","op":"neq","value":"premium"}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `neq operator fails when attribute matches`() {
        engine.setUserAttribute("plan", "premium")
        val msg = message(audienceJson = """[{"field":"plan","op":"neq","value":"premium"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 3. Audience conditions — gt / lt (numeric)
    // ---------------------------------------------------------------------

    @Test
    fun `gt operator passes when attribute is numerically greater`() {
        engine.setUserAttribute("session_count", "10")
        val msg = message(audienceJson = """[{"field":"session_count","op":"gt","value":5}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `gt operator fails when attribute is equal or less`() {
        engine.setUserAttribute("session_count", "5")
        val msg = message(audienceJson = """[{"field":"session_count","op":"gt","value":5}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `gt operator fails when attribute is not numeric`() {
        engine.setUserAttribute("session_count", "many")
        val msg = message(audienceJson = """[{"field":"session_count","op":"gt","value":5}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `lt operator passes when attribute is numerically less`() {
        engine.setUserAttribute("session_count", "3")
        val msg = message(audienceJson = """[{"field":"session_count","op":"lt","value":5}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `lt operator fails when attribute is equal or greater`() {
        engine.setUserAttribute("session_count", "7")
        val msg = message(audienceJson = """[{"field":"session_count","op":"lt","value":5}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 3. Audience conditions — in / nin / contains
    // ---------------------------------------------------------------------

    @Test
    fun `in operator passes when attribute is in the list`() {
        engine.setUserAttribute("tier", "gold")
        val msg = message(audienceJson = """[{"field":"tier","op":"in","value":["gold","platinum"]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `in operator fails when attribute is not in the list`() {
        engine.setUserAttribute("tier", "bronze")
        val msg = message(audienceJson = """[{"field":"tier","op":"in","value":["gold","platinum"]}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `in operator fails when value is not an array`() {
        engine.setUserAttribute("tier", "gold")
        val msg = message(audienceJson = """[{"field":"tier","op":"in","value":"gold"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `nin operator passes when attribute is not in the list`() {
        engine.setUserAttribute("tier", "bronze")
        val msg = message(audienceJson = """[{"field":"tier","op":"nin","value":["gold","platinum"]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `nin operator fails when attribute is in the list`() {
        engine.setUserAttribute("tier", "gold")
        val msg = message(audienceJson = """[{"field":"tier","op":"nin","value":["gold","platinum"]}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `contains operator passes when attribute contains the value`() {
        engine.setUserAttribute("email", "user@example.com")
        val msg = message(audienceJson = """[{"field":"email","op":"contains","value":"@example.com"}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `contains operator fails when attribute does not contain the value`() {
        engine.setUserAttribute("email", "user@other.org")
        val msg = message(audienceJson = """[{"field":"email","op":"contains","value":"@example.com"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 3. Audience conditions — combination and edge cases
    // ---------------------------------------------------------------------

    @Test
    fun `multiple conditions all passing is eligible`() {
        engine.setUserAttribute("plan", "premium")
        engine.setUserAttribute("session_count", "10")
        val msg = message(
            audienceJson = """[
                {"field":"plan","op":"eq","value":"premium"},
                {"field":"session_count","op":"gt","value":5}
            ]"""
        )

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `multiple conditions are ANDed so one failure kills eligibility`() {
        engine.setUserAttribute("plan", "premium")
        engine.setUserAttribute("session_count", "2")
        val msg = message(
            audienceJson = """[
                {"field":"plan","op":"eq","value":"premium"},
                {"field":"session_count","op":"gt","value":5}
            ]"""
        )

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `missing user attribute makes message not eligible for positive operators`() {
        val msg = message(audienceJson = """[{"field":"favorite_color","op":"eq","value":"blue"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `neq operator passes when attribute is missing`() {
        // Absence satisfies a negative match: an absent value is trivially not
        // equal to the target, so a "not premium" campaign reaches users whose
        // plan is unknown.
        val msg = message(audienceJson = """[{"field":"plan","op":"neq","value":"premium"}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `nin operator passes when attribute is missing`() {
        val msg = message(audienceJson = """[{"field":"tier","op":"nin","value":["gold","platinum"]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `null audience json is eligible for everyone`() {
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = null)))
    }

    @Test
    fun `empty audience json is eligible for everyone`() {
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "")))
    }

    @Test
    fun `empty audience array is eligible for everyone`() {
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[]")))
    }

    @Test
    fun `malformed audience json makes message not eligible`() {
        // Documented actual behavior: audience parse errors fail CLOSED
        // (return false), unlike frequency parse errors which fail open.
        val msg = message(audienceJson = "{not valid json!")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `unknown audience operator makes message not eligible`() {
        engine.setUserAttribute("plan", "premium")
        val msg = message(audienceJson = """[{"field":"plan","op":"regex","value":"prem.*"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `removed user attribute no longer satisfies condition`() {
        engine.setUserAttribute("plan", "premium")
        engine.removeUserAttribute("plan")
        val msg = message(audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 4. Combined checks
    // ---------------------------------------------------------------------

    @Test
    fun `valid dates and frequency ok but audience failing is not eligible`() {
        val now = System.currentTimeMillis()
        engine.setUserAttribute("plan", "free")
        val msg = message(
            startDate = Date(now - hourMillis),
            endDate = Date(now + hourMillis),
            frequencyJson = """{"type":"one_time"}""",
            audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]"""
        )
        stubDisplayCount(msg.id, 0)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `valid dates and audience ok but frequency exceeded is not eligible`() {
        val now = System.currentTimeMillis()
        engine.setUserAttribute("plan", "premium")
        val msg = message(
            startDate = Date(now - hourMillis),
            endDate = Date(now + hourMillis),
            frequencyJson = """{"type":"capped","count":2}""",
            audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]"""
        )
        stubDisplayCount(msg.id, 2)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `expired date window is not eligible even when frequency and audience pass`() {
        val now = System.currentTimeMillis()
        engine.setUserAttribute("plan", "premium")
        val msg = message(
            startDate = Date(now - 2 * hourMillis),
            endDate = Date(now - hourMillis),
            frequencyJson = """{"type":"one_time"}""",
            audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]"""
        )
        stubDisplayCount(msg.id, 0)

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `all checks passing makes message eligible`() {
        val now = System.currentTimeMillis()
        engine.setUserAttribute("plan", "premium")
        val msg = message(
            startDate = Date(now - hourMillis),
            endDate = Date(now + hourMillis),
            frequencyJson = """{"type":"recurring","interval":10}""",
            audienceJson = """[{"field":"plan","op":"eq","value":"premium"}]"""
        )
        stubDisplayCount(msg.id, 1)
        stubLastDisplay(msg.id, now - 15_000L)

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 6. Trigger params and audience are separate vocabularies
    //    (regression: params used to be written into the attribute store, and
    //     audience conditions used to resolve against them)
    // ---------------------------------------------------------------------

    private fun triggerMessage(triggerJson: String, id: String = "msg-1") = IAMMessage(
        id = id,
        position = IAMPosition.CENTER,
        htmlContent = "<html></html>",
        displayDuration = 0L,
        shouldDismissOnTap = false,
        actionsJson = "{}",
        startDate = null,
        endDate = null,
        priority = 1,
        audienceJson = null,
        frequencyJson = null,
        triggerJson = triggerJson
    )

    @Test
    fun `audience does not resolve against trigger params`() {
        // cart_value exists only as a trigger param, never as subscriber/device data,
        // so an audience condition on it must not match.
        val msg = message(audienceJson = "[${cond("cart_value", "eq", "250")}]")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `a trigger param cannot shadow a built-in audience field`() {
        // Even a param named `platform` must not affect audience evaluation.
        val wantsWeb = message(audienceJson = "[${cond("platform", "eq", "Web")}]")
        val wantsAndroid = message(audienceJson = "[${cond("platform", "eq", "Android")}]")

        assertFalse(engine.matchesTriggerConditions(triggerMessage("""{"type":"custom","event":"e"}"""), mapOf("platform" to "Web")).let {
            // the trigger side is unconstrained here; what matters is the audience side
            engine.isEligibleForDisplay(wantsWeb)
        })
        assertTrue(engine.isEligibleForDisplay(wantsAndroid))
    }

    @Test
    fun `trigger params are never written to the attribute store`() {
        engine.setUserAttribute("plan", "gold")
        val trigger = triggerMessage("""{"type":"custom","event":"e"}""")

        engine.matchesTriggerConditions(trigger, mapOf("plan" to "trial"))

        // The stored attribute is untouched — it used to be overwritten and then
        // deleted outright by the old set/remove approach.
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[${cond("plan", "eq", "gold")}]")))
    }

    // ---- trigger conditions ----

    @Test
    fun `no conditions matches any occurrence, even when params are passed`() {
        val trigger = triggerMessage("""{"type":"custom","event":"checkout"}""")

        assertTrue(engine.matchesTriggerConditions(trigger, null))
        assertTrue(
            "a parameterless campaign used to be SKIPPED when the call carried params",
            engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "250"))
        )
    }

    @Test
    fun `empty conditions array also matches any occurrence`() {
        val trigger = triggerMessage("""{"type":"custom","event":"e","match":"all","conditions":[]}""")

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("x" to "y")))
    }

    @Test
    fun `null conditions matches any occurrence`() {
        val trigger = triggerMessage("""{"type":"custom","event":"e","conditions":null}""")

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("x" to "y")))
    }

    @Test
    fun `a single condition object not wrapped in an array fails closed`() {
        // Present but not an array is malformed, not absent. Falling through to the
        // legacy branch would find no `parameters` and match EVERY occurrence, which
        // is the opposite of what the condition asks for.
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","conditions":
                 {"field":"cart_value","type":"number","op":"gte","value":["100"]}}"""
        )

        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "150")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "50")))
    }

    @Test
    fun `a scalar conditions value fails closed`() {
        val trigger = triggerMessage("""{"type":"custom","event":"checkout","conditions":"cart_value>100"}""")

        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "150")))
    }

    @Test
    fun `segments in a trigger condition never holds, even when the subscriber is in it`() {
        // The trigger asks "did this happen, with this data?"; the audience asks "is
        // this the right person?". A trigger occurrence carries no segments, so such a
        // condition must not quietly resolve against the subscriber's real membership.
        engine.applySubscriberState(setOf("vip"), emptyMap(), emptyMap(), "hash-a")
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","conditions":[
                 {"field":"segments","op":"includes","value":["vip"]}]}"""
        )

        assertFalse(engine.matchesTriggerConditions(trigger, emptyMap()))
    }

    @Test
    fun `segments excludes in a trigger does not short-circuit past a real condition`() {
        // Under `any` a vacuously-true segments condition would let the campaign fire
        // without the cart_value requirement ever being consulted.
        engine.applySubscriberState(setOf("vip"), emptyMap(), emptyMap(), "hash-a")
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","match":"any","conditions":[
                 {"field":"segments","op":"excludes","value":["gold"]},
                 {"field":"cart_value","type":"number","op":"gte","value":["100"]}]}"""
        )

        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "50")))
        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "150")))
    }

    @Test
    fun `a numeric trigger condition compares numerically, not lexically`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","match":"all","conditions":[
                 {"field":"cart_value","type":"number","op":"gte","value":["100"]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "250")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "50")))
        // "9" > "100" lexically; must not match.
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "9")))
    }

    @Test
    fun `a Double-shaped param matches an integer condition under the number type`() {
        // triggerIAMEvent stringifies via toString(), so 100.0 arrives as
        // "100.0" and used to never equal "100".
        val trigger = triggerMessage(
            """{"type":"custom","event":"e","match":"all","conditions":[
                 {"field":"amount","type":"number","op":"eq","value":["100"]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("amount" to "100.0")))
    }

    @Test
    fun `an untyped eq condition matches a Double-shaped param`() {
        // An untyped condition compares as TEXT, so the parameter's rendering is the
        // whole game: a React Native `120` arrives as java.lang.Double 120.0, and
        // Object.toString() made it "120.0" — never equal to the dashboard's "120",
        // while iOS matched. The parameters must be rendered through
        // IAMScalarString, exactly as the SDK's own trigger entry point does.
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","match":"all","conditions":[
                 {"field":"cart_value","op":"eq","value":["120"]}]}"""
        )

        assertTrue(
            engine.matchesTriggerConditions(
                trigger,
                IAMScalarString.stringifyValues(mapOf("cart_value" to 120.0))
            )
        )
        // Fractions are unaffected — they still have to match as authored.
        assertFalse(
            engine.matchesTriggerConditions(
                trigger,
                IAMScalarString.stringifyValues(mapOf("cart_value" to 120.5))
            )
        )
    }

    @Test
    fun `an untyped contains condition matches a Double-shaped param`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","match":"all","conditions":[
                 {"field":"order_id","op":"contains","value":["120"]}]}"""
        )

        assertTrue(
            engine.matchesTriggerConditions(
                trigger,
                IAMScalarString.stringifyValues(mapOf("order_id" to 120.0))
            )
        )
    }

    @Test
    fun `an untyped in condition matches a Double-shaped param`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","match":"all","conditions":[
                 {"field":"tier","op":"in","value":["1","2","3"]}]}"""
        )

        assertTrue(
            engine.matchesTriggerConditions(
                trigger,
                IAMScalarString.stringifyValues(mapOf("tier" to 2.0))
            )
        )
    }

    @Test
    fun `a condition value stored as a JSON number is rendered the same way`() {
        // The other side of the same coin: the dashboard normally sends condition
        // values as strings, but a stored `120.0` must not stop matching a
        // parameter of "120" under an untyped (text) comparison.
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","match":"all","conditions":[
                 {"field":"cart_value","op":"eq","value":[120.0]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "120")))
    }

    @Test
    fun `a legacy parameters value stored as a JSON number is rendered the same way`() {
        // Campaigns stored by an earlier build still use the `parameters` map, which
        // is exact string equality — so it needs the same rendering as `conditions`.
        val trigger = triggerMessage(
            """{"type":"custom","event":"checkout","parameters":{"cart_value":120.0}}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "120")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("cart_value" to "121")))
    }

    @Test
    fun `a segment name stored as a JSON number still matches`() {
        engine.applySubscriberState(setOf("120"), emptyMap(), emptyMap(), "hash-a")
        val msg = message(audienceJson = """[{"field":"segments","op":"includes","value":[120.0]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `extra params the campaign does not name are ignored`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"e","match":"all","conditions":[
                 {"field":"currency","op":"eq","value":["USD"]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(
            trigger, mapOf("currency" to "USD", "unrelated" to "whatever")
        ))
    }

    @Test
    fun `a condition naming a param the app did not send fails positively, passes negatively`() {
        val positive = triggerMessage(
            """{"type":"custom","event":"e","conditions":[{"field":"currency","op":"eq","value":["USD"]}]}"""
        )
        val negative = triggerMessage(
            """{"type":"custom","event":"e","conditions":[{"field":"currency","op":"neq","value":["USD"]}]}"""
        )

        assertFalse(engine.matchesTriggerConditions(positive, emptyMap()))
        assertTrue(engine.matchesTriggerConditions(negative, emptyMap()))
    }

    @Test
    fun `trigger match any needs only one condition to hold`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"e","match":"any","conditions":[
                 {"field":"source","op":"eq","value":["google"]},
                 {"field":"referred","type":"boolean","op":"eq","value":["true"]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("referred" to "true")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("referred" to "false")))
    }

    @Test
    fun `trigger match all needs every condition to hold`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"e","match":"all","conditions":[
                 {"field":"a","op":"eq","value":["1"]},
                 {"field":"b","op":"eq","value":["2"]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("a" to "1", "b" to "2")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("a" to "1")))
    }

    @Test
    fun `unknown trigger match mode fails closed`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"e","match":"most","conditions":[
                 {"field":"a","op":"eq","value":["1"]}]}"""
        )

        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("a" to "1")))
    }

    @Test
    fun `legacy parameters map is still matched as exact equality`() {
        // Campaigns stored by an earlier build stay in Room until the next sync.
        val trigger = triggerMessage("""{"type":"custom","event":"e","parameters":{"type":"permission"}}""")

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("type" to "permission")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("type" to "other")))
        assertFalse(engine.matchesTriggerConditions(trigger, emptyMap()))
    }

    @Test
    fun `malformed trigger json fails closed`() {
        assertFalse(engine.matchesTriggerConditions(triggerMessage("{not json"), emptyMap()))
    }

    // ---------------------------------------------------------------------
    // 7. Audience groups — OR across groups, AND within (and the legacy shape)
    // ---------------------------------------------------------------------

    private fun group(vararg conditions: String, match: String = "all") =
        """{"match":"$match","conditions":[${conditions.joinToString(",")}]}"""

    private fun audience(vararg groups: String, match: String = "any") =
        """{"match":"$match","groups":[${groups.joinToString(",")}]}"""

    private val platformIsAndroid = """{"field":"platform","op":"eq","value":["Android"]}"""
    private val platformIsWeb = """{"field":"platform","op":"eq","value":["Web"]}"""
    private val planIsGold = """{"field":"plan","op":"eq","value":["gold"]}"""

    @Test
    fun `legacy flat array is still evaluated as one ANDed group`() {
        engine.setUserAttribute("plan", "gold")
        val msg = message(
            audienceJson = """[$platformIsAndroid,$planIsGold]"""
        )

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `legacy flat array fails when one condition fails`() {
        engine.setUserAttribute("plan", "free")
        val msg = message(audienceJson = """[$platformIsAndroid,$planIsGold]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `single group with all conditions true is eligible`() {
        engine.setUserAttribute("plan", "gold")
        val msg = message(audienceJson = audience(group(platformIsAndroid, planIsGold)))

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `single group fails when any condition fails under match all`() {
        engine.setUserAttribute("plan", "free")
        val msg = message(audienceJson = audience(group(platformIsAndroid, planIsGold)))

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `match any across groups passes when only the second group matches`() {
        engine.setUserAttribute("plan", "gold")
        val failing = group(platformIsWeb)
        val passing = group(planIsGold)
        val msg = message(audienceJson = audience(failing, passing, match = "any"))

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `match any across groups fails when no group matches`() {
        engine.setUserAttribute("plan", "free")
        val msg = message(audienceJson = audience(group(platformIsWeb), group(planIsGold), match = "any"))

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `match all across groups requires every group to match`() {
        engine.setUserAttribute("plan", "gold")
        val bothOk = audience(group(platformIsAndroid), group(planIsGold), match = "all")
        val oneBad = audience(group(platformIsWeb), group(planIsGold), match = "all")

        assertTrue(engine.isEligibleForDisplay(message(audienceJson = bothOk)))
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = oneBad)))
    }

    @Test
    fun `match any within a group passes when one condition holds`() {
        // CNF-style: a group that ORs its own conditions.
        val msg = message(
            audienceJson = audience(group(platformIsWeb, platformIsAndroid, match = "any"), match = "all")
        )

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `match any within a group fails when no condition holds`() {
        engine.setUserAttribute("plan", "free")
        val msg = message(
            audienceJson = audience(group(platformIsWeb, planIsGold, match = "any"), match = "all")
        )

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `absent or empty groups means everyone`() {
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any"}""")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any","groups":[]}""")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[]")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = null)))
    }

    @Test
    fun `a group with no conditions is skipped, not treated as vacuously true`() {
        // An empty AND is logically true; honouring that would let one stray empty
        // group open the campaign to everybody.
        engine.setUserAttribute("plan", "free")
        val msg = message(
            audienceJson = audience(group(), group(planIsGold), match = "any")
        )

        assertFalse("empty group must not open the campaign", engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `skipping an empty group still lets a real group match`() {
        engine.setUserAttribute("plan", "gold")
        val msg = message(audienceJson = audience(group(), group(planIsGold), match = "any"))

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `groups present but all empty fails closed`() {
        val msg = message(audienceJson = audience(group(), group(), match = "any"))

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `unknown match mode fails closed`() {
        val top = message(audienceJson = """{"match":"some","groups":[${group(platformIsAndroid)}]}""")
        val inner = message(
            audienceJson = """{"match":"any","groups":[{"match":"most","conditions":[$platformIsAndroid]}]}"""
        )

        assertFalse(engine.isEligibleForDisplay(top))
        assertFalse(engine.isEligibleForDisplay(inner))
    }

    @Test
    fun `match mode is case-insensitive`() {
        val msg = message(
            audienceJson = """{"match":"ANY","groups":[{"match":"ALL","conditions":[$platformIsAndroid]}]}"""
        )

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `malformed audience json fails closed`() {
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = "{not json")))
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = """{"groups":[42]}""")))
    }

    @Test
    fun `index-keyed groups object fails closed rather than opening reach`() {
        // A serializer re-encoding an array as an index-keyed object is a malformed
        // audience, not an absent one. The group inside would match, so reading it
        // as "no criteria" would show a segment-targeted campaign to everybody.
        engine.setUserAttribute("plan", "gold")
        val msg = message(
            audienceJson = """{"match":"any","groups":{"0":{"match":"all","conditions":[$planIsGold]}}}"""
        )

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `scalar groups value fails closed`() {
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any","groups":"all-users"}""")))
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any","groups":7}""")))
    }

    @Test
    fun `absent null and empty groups all still mean everyone`() {
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any"}""")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any","groups":null}""")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = """{"match":"any","groups":[]}""")))
    }


    // ---------------------------------------------------------------------
    // 8. Field catalogue and typed comparison
    // ---------------------------------------------------------------------

    private fun cond(field: String, op: String, vararg values: String, type: String? = null): String {
        val t = if (type != null) ""","type":"$type"""" else ""
        val v = values.joinToString(",") { "\"$it\"" }
        return """{"field":"$field"$t,"op":"$op","value":[$v]}"""
    }

    @Test
    fun `os_version is the marketing version, compared component-wise`() {
        // Robolectric @Config(sdk = 28) reports RELEASE "9".
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[${cond("os_version", "eq", "9")}]")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[${cond("os_version", "gte", "9")}]")))
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[${cond("os_version", "gt", "8.1.0")}]")))
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = "[${cond("os_version", "gt", "14")}]")))
    }

    @Test
    fun `version comparison is not lexical`() {
        engine.setUserAttribute("ver", "14")
        // "14" < "8" as text, but 14 > 8 as a version.
        val msg = message(audienceJson = "[${cond("ver", "gt", "8.1.0", type = "version")}]")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `missing version components count as zero`() {
        engine.setUserAttribute("ver", "8.1")
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ver", "eq", "8.1.0", type = "version")}]")
        ))
    }

    @Test
    fun `a version codename cannot be ordered and fails the condition`() {
        engine.setUserAttribute("ver", "VanillaIceCream")
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ver", "gt", "13", type = "version")}]")
        ))
    }

    @Test
    fun `not-a-number cannot be ordered and fails the condition`() {
        // "NaN" parses as a Double, and NaN answers false to every comparison — which
        // Kotlin's total ordering then reports as greater than everything, so a
        // nonsense value would match any threshold.
        engine.setUserAttribute("ltv", "NaN")
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "gte", "500", type = "number")}]")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "gt", "500", type = "number")}]")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "lt", "500", type = "number")}]")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "eq", "NaN", type = "number")}]")
        ))
    }

    @Test
    fun `not-a-number on the target side also fails the condition`() {
        engine.setUserAttribute("ltv", "750")
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "gte", "NaN", type = "number")}]")
        ))
    }

    @Test
    fun `pre-release suffix compares on the numeric core`() {
        engine.setUserAttribute("appver", "2.3.1-beta")
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("appver", "gte", "2.3.0", type = "version")}]")
        ))
    }

    @Test
    fun `gte and lte include the boundary`() {
        engine.setUserAttribute("score", "100")
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("score", "gte", "100", type = "number")}]")
        ))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("score", "lte", "100", type = "number")}]")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("score", "gt", "100", type = "number")}]")
        ))
    }

    @Test
    fun `numeric equality ignores formatting differences`() {
        engine.setUserAttribute("ltv", "100.0")
        assertTrue(
            "100.0 must equal 100 under the number type",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("ltv", "eq", "100", type = "number")}]"))
        )
        // As a string the two differ.
        assertFalse(
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("ltv", "eq", "100", type = "string")}]"))
        )
    }

    @Test
    fun `ordering on a declared string field fails rather than comparing lexically`() {
        engine.setUserAttribute("tier", "9")
        assertFalse(
            "gt on a declared string must not fall back to text order",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("tier", "gt", "10", type = "string")}]"))
        )
    }

    @Test
    fun `ordering on a built-in string field fails`() {
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("platform", "gt", "Aardvark")}]")
        ))
    }

    @Test
    fun `an undeclared attribute infers numeric ordering`() {
        engine.setUserAttribute("cart_value", "250")
        assertTrue(engine.isEligibleForDisplay(message(audienceJson = "[${cond("cart_value", "gt", "100")}]")))
        assertFalse(engine.isEligibleForDisplay(message(audienceJson = "[${cond("cart_value", "gt", "500")}]")))
    }

    @Test
    fun `a declared type on a built-in field is ignored`() {
        // Claiming os_version is a string must not turn gt into lexical order —
        // lexically "9" > "14" is true, numerically/version-wise it is also true,
        // so use a pair where the two disagree: 9 vs 10.
        val msg = message(audienceJson = "[${cond("os_version", "lt", "10", type = "string")}]")

        assertTrue("catalogue type must win over the declared one", engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `boolean accepts only true and false`() {
        engine.setUserAttribute("flag", "true")
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("flag", "eq", "TRUE", type = "boolean")}]")
        ))
        engine.setUserAttribute("flag", "1")
        assertFalse(
            "1 is not a boolean",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("flag", "eq", "true", type = "boolean")}]"))
        )
    }

    @Test
    fun `contains is refused for non-string types`() {
        engine.setUserAttribute("ltv", "1500")
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "contains", "50", type = "number")}]")
        ))
        // The same check as a string does match.
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ltv", "contains", "50", type = "string")}]")
        ))
    }

    @Test
    fun `membership uses the field type, so 8_1 matches 8_1_0`() {
        engine.setUserAttribute("ver", "8.1")
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("ver", "in", "7.0", "8.1.0", type = "version")}]")
        ))
    }

    @Test
    fun `neq fails closed when the values cannot be compared`() {
        engine.setUserAttribute("ltv", "abc")
        assertFalse(
            "an uncomparable pair must not be reported as different",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("ltv", "neq", "100", type = "number")}]"))
        )
    }

    @Test
    fun `device_model is not a filterable field`() {
        // Removed from the catalogue: opaque part numbers with OEM-dependent casing
        // are not reliably authorable, so a condition on it must fail closed.
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("device_model", "contains", "Pixel")}]")
        ))
    }

    @Test
    fun `device_region is available and country is deliberately not`() {
        // A condition on `country` must fail closed rather than silently resolving to
        // the locale region, because the backend also has an IP-derived country.
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("device_region", "neq", "ZZ")}]")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("country", "eq", "US")}]")
        ))
    }

    @Test
    fun `platform timezone and notification_enabled resolve`() {
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("platform", "eq", "Android")}]")
        ))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("timezone", "neq", "Nowhere/Nowhere")}]")
        ))
        // Robolectric grants notifications by default.
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("notification_enabled", "eq", "true")}]")
        ))
    }

    @Test
    fun `app_version resolves from the host package`() {
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("app_version", "neq", "0.0.0-nonexistent")}]")
        ))
    }

    // ---------------------------------------------------------------------
    // 9. Subscriber-backed fields: segments, attr.*, geo — and deferral
    // ---------------------------------------------------------------------

    private fun loadSubscriberState(
        segments: Set<String> = emptySet(),
        attributes: Map<String, String> = emptyMap(),
        scalars: Map<String, String> = emptyMap(),
        identity: String = "hash-a"
    ) = engine.applySubscriberState(segments, attributes, scalars, identity)

    // ---- identity changes ----

    @Test
    fun `a snapshot is discarded when the subscriber identity changes`() {
        loadSubscriberState(
            segments = setOf("vip"),
            attributes = mapOf("plan" to "gold"),
            scalars = mapOf("geo_country" to "India"),
            identity = "hash-a"
        )
        val vipOnly = message(audienceJson = """[{"field":"segments","op":"includes","value":["vip"]}]""")
        assertTrue(engine.isEligibleForDisplay(vipOnly))

        engine.invalidateSubscriberStateIfIdentityChanged("hash-b")

        // The previous subscriber's data must not answer for this one, and the state
        // must read as never-fetched so such campaigns defer instead of mis-matching.
        assertFalse(engine.isEligibleForDisplay(vipOnly))
        assertTrue(engine.requiresUnavailableSubscriberState(vipOnly))
        assertFalse(
            engine.isEligibleForDisplay(
                message(audienceJson = """[{"field":"attr.plan","op":"eq","value":["gold"]}]""")
            )
        )
        assertFalse(
            engine.isEligibleForDisplay(
                message(audienceJson = """[{"field":"geo_country","op":"eq","value":["India"]}]""")
            )
        )
    }

    @Test
    fun `a snapshot survives when the subscriber identity is unchanged`() {
        loadSubscriberState(segments = setOf("vip"), identity = "hash-a")
        val vipOnly = message(audienceJson = """[{"field":"segments","op":"includes","value":["vip"]}]""")

        engine.invalidateSubscriberStateIfIdentityChanged("hash-a")

        assertTrue(engine.isEligibleForDisplay(vipOnly))
        assertFalse(engine.requiresUnavailableSubscriberState(vipOnly))
    }

    @Test
    fun `losing the subscriber discards the snapshot`() {
        // Unsubscribe clears the hash, so the snapshot describes nobody.
        loadSubscriberState(segments = setOf("vip"), identity = "hash-a")
        val vipOnly = message(audienceJson = """[{"field":"segments","op":"includes","value":["vip"]}]""")

        engine.invalidateSubscriberStateIfIdentityChanged("")

        assertFalse(engine.isEligibleForDisplay(vipOnly))
        assertTrue(engine.requiresUnavailableSubscriberState(vipOnly))
    }

    @Test
    fun `locally set attributes survive an identity change`() {
        // Only the `attr.` namespace comes from the subscriber record; attributes the
        // app set itself are its own state and are not ours to drop.
        engine.setUserAttribute("theme", "dark")
        loadSubscriberState(attributes = mapOf("plan" to "gold"), identity = "hash-a")

        engine.invalidateSubscriberStateIfIdentityChanged("hash-b")

        assertTrue(
            engine.isEligibleForDisplay(
                message(audienceJson = """[{"field":"theme","op":"eq","value":["dark"]}]""")
            )
        )
        assertFalse(
            engine.isEligibleForDisplay(
                message(audienceJson = """[{"field":"attr.plan","op":"eq","value":["gold"]}]""")
            )
        )
    }

    @Test
    fun `segments includes matches when the subscriber is in any listed segment`() {
        loadSubscriberState(segments = setOf("qatest", "vip"))
        val msg = message(audienceJson = """[{"field":"segments","op":"includes","value":["gold","vip"]}]""")

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `segments includes fails when there is no overlap`() {
        loadSubscriberState(segments = setOf("qatest"))
        val msg = message(audienceJson = """[{"field":"segments","op":"includes","value":["gold","vip"]}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `segments excludes is the inverse of includes`() {
        loadSubscriberState(segments = setOf("qatest"))
        val notGold = message(audienceJson = """[{"field":"segments","op":"excludes","value":["gold"]}]""")
        val notQatest = message(audienceJson = """[{"field":"segments","op":"excludes","value":["qatest"]}]""")

        assertTrue(engine.isEligibleForDisplay(notGold))
        assertFalse(engine.isEligibleForDisplay(notQatest))
    }

    @Test
    fun `scalar operators are refused on segments`() {
        // `eq` must not be quietly reinterpreted as membership.
        loadSubscriberState(segments = setOf("vip"))
        val msg = message(audienceJson = """[{"field":"segments","op":"eq","value":["vip"]}]""")

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `segments with no cached segments excludes everything and includes nothing`() {
        loadSubscriberState(segments = emptySet())

        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = """[{"field":"segments","op":"includes","value":["vip"]}]""")
        ))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = """[{"field":"segments","op":"excludes","value":["vip"]}]""")
        ))
    }

    @Test
    fun `attributes resolve under the attr namespace`() {
        loadSubscriberState(attributes = mapOf("plan" to "gold", "ltv" to "750"))

        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("attr.plan", "eq", "gold")}]")
        ))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("attr.ltv", "gte", "500", type = "number")}]")
        ))
    }

    @Test
    fun `an attribute cannot shadow a built-in field`() {
        // An attribute literally named "language" lands at attr.language, so the
        // built-in stays intact.
        loadSubscriberState(attributes = mapOf("language" to "zz"))

        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("attr.language", "eq", "zz")}]")
        ))
        assertFalse(
            "the built-in language must not have been overwritten",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("language", "eq", "zz")}]"))
        )
    }

    @Test
    fun `applying subscriber state replaces attributes rather than merging`() {
        loadSubscriberState(attributes = mapOf("plan" to "gold"))
        loadSubscriberState(attributes = mapOf("tier" to "silver"))

        assertFalse(
            "a removed attribute must disappear",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("attr.plan", "eq", "gold")}]"))
        )
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("attr.tier", "eq", "silver")}]")
        ))
    }

    @Test
    fun `a scalar omitted by a later fetch is cleared, not left stale`() {
        loadSubscriberState(scalars = mapOf("city" to "Santa Cruz", "geo_country" to "India"))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("city", "eq", "Santa Cruz")}]")
        ))

        // Second snapshot has no city — e.g. the subscriber moved, or the site turned
        // geo off. The old value must not survive.
        loadSubscriberState(scalars = mapOf("geo_country" to "India"))

        assertFalse(
            "a stale scalar must not outlive the snapshot it came from",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("city", "eq", "Santa Cruz")}]"))
        )
        assertTrue(
            "scalars still present must survive",
            engine.isEligibleForDisplay(message(audienceJson = "[${cond("geo_country", "eq", "India")}]"))
        )
    }

    @Test
    fun `geo fields resolve from the cached subscriber state`() {
        loadSubscriberState(scalars = mapOf(
            "city" to "Santa Cruz", "state" to "Maharashtra", "geo_country" to "India"
        ))

        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("geo_country", "eq", "India")}]")
        ))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("city", "eq", "Santa Cruz")}]")
        ))
    }

    @Test
    fun `geo_country and device_region are independent`() {
        loadSubscriberState(scalars = mapOf("geo_country" to "India"))
        // Robolectric's locale region is US, so the two genuinely differ.
        val bothMustHold = message(
            audienceJson = "[${cond("geo_country", "eq", "India")},${cond("device_region", "eq", "US")}]"
        )

        assertTrue(engine.isEligibleForDisplay(bothMustHold))
    }

    @Test
    fun `has_unsubscribed is a boolean`() {
        loadSubscriberState(scalars = mapOf("has_unsubscribed" to "false"))
        assertTrue(engine.isEligibleForDisplay(
            message(audienceJson = "[${cond("has_unsubscribed", "eq", "false")}]")
        ))
    }

    // ---- deferral ----

    @Test
    fun `a campaign needing subscriber data is deferred before any fetch`() {
        val msg = message(audienceJson = """[{"field":"segments","op":"includes","value":["vip"]}]""")

        assertTrue(engine.requiresUnavailableSubscriberState(msg))
    }

    @Test
    fun `attr and geo conditions also defer`() {
        assertTrue(engine.requiresUnavailableSubscriberState(
            message(audienceJson = "[${cond("attr.plan", "eq", "gold")}]")
        ))
        assertTrue(engine.requiresUnavailableSubscriberState(
            message(audienceJson = "[${cond("geo_country", "eq", "India")}]")
        ))
    }

    @Test
    fun `deferral stops once subscriber state has been fetched`() {
        val msg = message(audienceJson = """[{"field":"segments","op":"includes","value":["vip"]}]""")
        assertTrue(engine.requiresUnavailableSubscriberState(msg))

        // An empty but successful fetch still counts as "we know".
        loadSubscriberState()

        assertFalse(engine.requiresUnavailableSubscriberState(msg))
    }

    @Test
    fun `device-only campaigns are never deferred`() {
        assertFalse(engine.requiresUnavailableSubscriberState(
            message(audienceJson = "[${cond("platform", "eq", "Android")}]")
        ))
        assertFalse(engine.requiresUnavailableSubscriberState(message(audienceJson = null)))
    }

    @Test
    fun `deferral inspects conditions inside groups too`() {
        val grouped = message(
            audienceJson = audience(group(cond("platform", "eq", "Android")), group(cond("attr.plan", "eq", "gold")))
        )

        assertTrue(engine.requiresUnavailableSubscriberState(grouped))
    }

    @Test
    fun `a negative condition on unavailable subscriber data defers instead of opening the campaign`() {
        // Without deferral this group would PASS (a negative operator succeeds against a
        // missing value), and with OR that alone would show the campaign to everyone.
        val msg = message(
            audienceJson = audience(group(cond("geo_country", "neq", "India")), match = "any")
        )

        assertTrue(engine.requiresUnavailableSubscriberState(msg))
        // Confirm the hazard is real: evaluated directly, it would have matched.
        assertTrue(engine.isEligibleForDisplay(msg))
    }

    // ---------------------------------------------------------------------
    // 10. JSON-null / blank tolerance on optional fields
    //     (org.json's optString returns the literal "null" for a JSON null and
    //      "" for an empty value — neither should fail a campaign closed)
    // ---------------------------------------------------------------------

    @Test
    fun `an explicit null match is treated as absent, not as an unknown mode`() {
        val topNull = message(
            audienceJson = """{"match":null,"groups":[{"match":"all","conditions":[$platformIsAndroid]}]}"""
        )
        val groupNull = message(
            audienceJson = """{"match":"any","groups":[{"match":null,"conditions":[$platformIsAndroid]}]}"""
        )

        assertTrue("a null top-level match must default, not fail closed", engine.isEligibleForDisplay(topNull))
        assertTrue("a null group match must default, not fail closed", engine.isEligibleForDisplay(groupNull))
    }

    @Test
    fun `a blank match is treated as absent`() {
        val msg = message(
            audienceJson = """{"match":"","groups":[{"match":"  ","conditions":[$platformIsAndroid]}]}"""
        )

        assertTrue(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `an explicit null match on a trigger is treated as absent`() {
        val trigger = triggerMessage(
            """{"type":"custom","event":"e","match":null,"conditions":[{"field":"a","op":"eq","value":["1"]}]}"""
        )

        assertTrue(engine.matchesTriggerConditions(trigger, mapOf("a" to "1")))
        assertFalse(engine.matchesTriggerConditions(trigger, mapOf("a" to "2")))
    }

    @Test
    fun `a genuinely unknown match still fails closed`() {
        val msg = message(
            audienceJson = """{"match":"some","groups":[{"match":"all","conditions":[$platformIsAndroid]}]}"""
        )

        assertFalse(engine.isEligibleForDisplay(msg))
    }

    @Test
    fun `a null or blank field or op fails the condition`() {
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = """[{"field":null,"op":"eq","value":["x"]}]""")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = """[{"field":"platform","op":null,"value":["Android"]}]""")
        ))
        assertFalse(engine.isEligibleForDisplay(
            message(audienceJson = """[{"field":"  ","op":"eq","value":["x"]}]""")
        ))
    }
}
