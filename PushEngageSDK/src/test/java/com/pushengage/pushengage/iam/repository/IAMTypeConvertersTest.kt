package com.pushengage.pushengage.iam.repository

import com.pushengage.pushengage.iam.model.IAMAction
import com.pushengage.pushengage.iam.model.IAMActionType
import com.pushengage.pushengage.iam.model.IAMFrequency
import com.pushengage.pushengage.iam.model.IAMFrequencyType
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * JVM unit tests for [IAMTypeConverters]. Verifies Gson round-trips for the
 * JSON-backed columns, that new writes use the lowercase wire values from the
 * backend contract (@SerializedName), and that legacy JSON persisted by older
 * SDK builds (uppercase enum names / iOS alternates) still deserializes.
 */
class IAMTypeConvertersTest {

    private val converters = IAMTypeConverters()

    // ---- Date <-> Long ----

    @Test
    fun date_roundTripsThroughTimestamp() {
        val date = Date(1751328000000L)
        assertEquals(date, converters.fromTimestamp(converters.dateToTimestamp(date)))
        assertEquals(1751328000000L, converters.dateToTimestamp(converters.fromTimestamp(1751328000000L)))
    }

    @Test
    fun date_nullHandling() {
        assertNull(converters.fromTimestamp(null))
        assertNull(converters.dateToTimestamp(null))
    }

    // ---- IAMPosition <-> String ----

    @Test
    fun position_roundTripsThroughEnumName() {
        for (position in IAMPosition.values()) {
            val stored = converters.positionToString(position)
            assertEquals("Room stores the enum name", position.name, stored)
            assertEquals(position, converters.fromPositionString(stored))
        }
    }

    @Test
    fun position_nullHandling() {
        assertNull(converters.positionToString(null))
        assertNull(converters.fromPositionString(null))
    }

    // ---- actions map ----

    @Test
    fun actions_roundTripPreservesMap() {
        val actions = mapOf(
            "cta" to IAMAction(
                type = IAMActionType.OPEN_URL,
                parameters = mapOf("url" to "https://pushengage.com"),
                label = "Visit"
            ),
            "dismiss_action" to IAMAction(type = IAMActionType.DISMISS, label = "Not Now")
        )

        val restored = converters.fromActionsJson(converters.actionsToJson(actions))

        assertEquals(actions, restored)
    }

    @Test
    fun actions_newWritesEmitLowercaseWireValues() {
        val json = converters.actionsToJson(
            mapOf(
                "a" to IAMAction(type = IAMActionType.OPEN_URL),
                "b" to IAMAction(type = IAMActionType.REQUEST_NOTIFICATION_PERMISSION),
                "c" to IAMAction(type = IAMActionType.DISMISS),
                "d" to IAMAction(type = IAMActionType.CUSTOM)
            )
        )!!

        assertTrue(json.contains("\"open_url\""))
        assertTrue(json.contains("\"request_notification_permission\""))
        assertTrue(json.contains("\"dismiss\""))
        assertTrue(json.contains("\"custom\""))
        assertFalse("must serialize the wire value, not the enum name", json.contains("OPEN_URL"))
        assertFalse("must serialize the wire value, not the enum name", json.contains("DISMISS"))
    }

    @Test
    fun actions_uppercaseTypeDoesNotResolve() {
        // Only the lowercase wire value is accepted. An uppercase `type` leaves the
        // action's type null, so the button is parsed but inert — the visible symptom
        // if the campaign API sends the wrong casing.
        val json = """
            {
              "close": {"type": "DISMISS"},
              "app": {"type": "CUSTOM"}
            }
        """.trimIndent()

        val actions = converters.fromActionsJson(json)!!

        assertNull(actions.getValue("close").type)
        assertNull(actions.getValue("app").type)
    }

    @Test
    fun actions_nullHandling() {
        assertNull(converters.actionsToJson(null))
        assertNull(converters.fromActionsJson(null))
    }

    // ---- audience list ----

    // Audience has no Room converter: `audience_json` is stored as a raw JSON
    // string, so Room never invoked one. The previous converter pair also encoded
    // the legacy flat-array shape, which no longer describes the wire format —
    // IAMRulesEngine is the single place that interprets it (and still accepts the
    // legacy array). Shape coverage lives in IAMRulesEngineTest / IAMWireFormatTest.

    // ---- frequency ----

    @Test
    fun frequency_roundTripPreservesAllFields() {
        val frequency = IAMFrequency(type = IAMFrequencyType.CAPPED, count = 3, interval = 86400L)

        val restored = converters.fromFrequencyJson(converters.frequencyToJson(frequency))

        assertEquals(frequency, restored)
    }

    @Test
    fun frequency_newWritesEmitLowercaseWireValues() {
        assertTrue(
            converters.frequencyToJson(IAMFrequency(type = IAMFrequencyType.ONE_TIME))!!
                .contains("\"one_time\"")
        )
        assertTrue(
            converters.frequencyToJson(IAMFrequency(type = IAMFrequencyType.RECURRING, interval = 60L))!!
                .contains("\"recurring\"")
        )
        assertTrue(
            converters.frequencyToJson(IAMFrequency(type = IAMFrequencyType.CAPPED, count = 2))!!
                .contains("\"capped\"")
        )
    }

    @Test
    fun frequency_legacyUppercaseJson_stillDeserializes() {
        val oneTime = converters.fromFrequencyJson("""{"type":"ONE_TIME"}""")!!
        val recurring = converters.fromFrequencyJson("""{"type":"RECURRING","interval":3600}""")!!
        val capped = converters.fromFrequencyJson("""{"type":"CAPPED","count":5}""")!!

        assertEquals(IAMFrequencyType.ONE_TIME, oneTime.type)
        assertEquals(IAMFrequencyType.RECURRING, recurring.type)
        assertEquals(3600L, recurring.interval)
        assertEquals(IAMFrequencyType.CAPPED, capped.type)
        assertEquals(5, capped.count)
    }

    @Test
    fun frequency_iosLegacyHyphenatedValue_stillDeserializes() {
        val frequency = converters.fromFrequencyJson("""{"type":"one-time"}""")!!
        assertEquals(IAMFrequencyType.ONE_TIME, frequency.type)
    }

    @Test
    fun frequency_nullHandling() {
        assertNull(converters.frequencyToJson(null))
        assertNull(converters.fromFrequencyJson(null))
    }

    // ---- trigger ----

    @Test
    fun trigger_roundTripPreservesAllFields() {
        val trigger = IAMTriggerCondition(
            type = "custom",
            event = "permission_prompt",
            match = "all",
            conditions = listOf(
                mapOf("field" to "screen", "op" to "eq", "value" to listOf("home"))
            )
        )

        val restored = converters.fromTriggerJson(converters.triggerToJson(trigger))

        assertEquals(trigger, restored)
    }

    @Test
    fun trigger_withoutConditions_roundTrips() {
        val trigger = IAMTriggerCondition(type = "auto", event = "app_open")

        val restored = converters.fromTriggerJson(converters.triggerToJson(trigger))

        assertEquals(trigger, restored)
        assertNull(restored!!.conditions)
    }

    @Test
    fun trigger_nullHandling() {
        assertNull(converters.triggerToJson(null))
        assertNull(converters.fromTriggerJson(null))
    }
}
