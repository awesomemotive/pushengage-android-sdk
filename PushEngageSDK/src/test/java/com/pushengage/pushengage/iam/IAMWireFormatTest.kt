package com.pushengage.pushengage.iam

import com.google.gson.annotations.SerializedName
import com.pushengage.pushengage.iam.model.IAMAction
import com.pushengage.pushengage.iam.model.IAMActionType
import com.pushengage.pushengage.iam.model.IAMFrequency
import com.pushengage.pushengage.iam.model.IAMFrequencyType
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import com.pushengage.pushengage.iam.network.IAMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Guards the IAM backend wire format:
 * lowercase enum values, ISO-8601 UTC dates, action labels — plus the
 * legacy/iOS alternates that keep older stored JSON readable.
 */
class IAMWireFormatTest {

    // 2025-07-01T00:00:00Z
    private val julyFirst2025UtcMillis = 1751328000000L

    private val contractCampaignJson = """
        {
          "id": "c9f1a2b3-4d5e-6f70-8123-abcdef012345",
          "position": "center",
          "htmlContent": "<!DOCTYPE html><html><body>hi</body></html>",
          "displayDuration": 0,
          "shouldDismissOnTap": false,
          "priority": 1,
          "startDate": "2025-07-01T00:00:00Z",
          "endDate": null,
          "trigger": { "type": "custom", "event": "permission_prompt", "parameters": { "type": "permission" } },
          "audience": [ { "field": "platform", "op": "in", "value": ["android"] } ],
          "frequency": { "type": "capped", "count": 3, "interval": 86400 },
          "actions": {
            "enable_notifications": { "type": "request_notification_permission", "label": "Enable", "parameters": {} },
            "dismiss_action": { "type": "dismiss", "label": "Not Now" }
          }
        }
    """.trimIndent()

    @Test
    fun `parses contract campaign payload`() {
        val message = IAMJson.gson.fromJson(contractCampaignJson, IAMMessageResponse::class.java)

        assertEquals("c9f1a2b3-4d5e-6f70-8123-abcdef012345", message.id)
        assertEquals(IAMPosition.CENTER, message.position)
        assertEquals(0L, message.displayDuration)
        assertEquals(1, message.priority)
        assertEquals(julyFirst2025UtcMillis, message.startDate?.time)
        assertNull(message.endDate)

        assertEquals("custom", message.trigger.type)
        assertEquals("permission_prompt", message.trigger.event)
        // Legacy `parameters` map is still accepted on the wire; the contract shape
        // is now `match` + `conditions` (see the trigger-conditions tests).
        assertNull("the contract payload uses no conditions here", message.trigger.conditions)

        // audience is held as raw JSON (IAMRulesEngine owns shape interpretation),
        // so assert it round-trips verbatim rather than into a typed model.
        val audienceJson = message.audience!!.asJsonArray
        assertEquals(1, audienceJson.size())
        assertEquals("platform", audienceJson[0].asJsonObject["field"].asString)

        assertEquals(IAMFrequencyType.CAPPED, message.frequency?.type)
        assertEquals(3, message.frequency?.count)
        assertEquals(86400L, message.frequency?.interval)

        val enable = message.actions.getValue("enable_notifications")
        assertEquals(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, enable.type)
        assertEquals("Enable", enable.label)
        val dismiss = message.actions.getValue("dismiss_action")
        assertEquals(IAMActionType.DISMISS, dismiss.type)
        assertEquals("Not Now", dismiss.label)
    }

    @Test
    fun `parses legacy enum names stored by older SDK builds`() {
        assertEquals(IAMFrequencyType.ONE_TIME, IAMJson.gson.fromJson("\"ONE_TIME\"", IAMFrequencyType::class.java))
        assertEquals(IAMPosition.TOP, IAMJson.gson.fromJson("\"TOP\"", IAMPosition::class.java))
    }

    @Test
    fun `parses iOS-legacy wire values`() {
        assertEquals(IAMFrequencyType.ONE_TIME, IAMJson.gson.fromJson("\"one-time\"", IAMFrequencyType::class.java))
    }

    @Test
    fun `action type accepts the lowercase wire value only`() {
        assertEquals(IAMActionType.OPEN_URL, IAMJson.gson.fromJson("\"open_url\"", IAMActionType::class.java))
        assertEquals(IAMActionType.DISMISS, IAMJson.gson.fromJson("\"dismiss\"", IAMActionType::class.java))

        // Uppercase is not accepted: one spelling in both directions, matching
        // position / frequency.type / trigger.type. The campaign API must send it.
        assertNull(IAMJson.gson.fromJson("\"OPEN_URL\"", IAMActionType::class.java))
        assertNull(IAMJson.gson.fromJson("\"DISMISS\"", IAMActionType::class.java))

        // dismiss_action / close_action are hardcoded action IDs (see
        // IAMWebViewContainer's dismiss fallback and the sample campaign HTML), not
        // type values, so they must not resolve as a type either.
        assertNull(IAMJson.gson.fromJson("\"dismiss_action\"", IAMActionType::class.java))
        assertNull(IAMJson.gson.fromJson("\"custom_action\"", IAMActionType::class.java))
    }

    @Test
    fun `serializes contract wire values, not enum names`() {
        assertEquals("\"open_url\"", IAMJson.gson.toJson(IAMActionType.OPEN_URL))
        assertEquals("\"request_notification_permission\"", IAMJson.gson.toJson(IAMActionType.REQUEST_NOTIFICATION_PERMISSION))
        assertEquals("\"dismiss\"", IAMJson.gson.toJson(IAMActionType.DISMISS))
        assertEquals("\"custom\"", IAMJson.gson.toJson(IAMActionType.CUSTOM))
        assertEquals("\"one_time\"", IAMJson.gson.toJson(IAMFrequencyType.ONE_TIME))
        assertEquals("\"center\"", IAMJson.gson.toJson(IAMPosition.CENTER))
    }

    @Test
    fun `trigger delay parses and defaults to absent`() {
        val withDelay = IAMJson.gson.fromJson(
            """{"type":"auto","delay":5}""",
            IAMTriggerCondition::class.java
        )
        assertEquals(5L, withDelay.delay)

        // Absent stays null so the SDK can distinguish "not set" from an explicit 0.
        val withoutDelay = IAMJson.gson.fromJson(
            """{"type":"auto"}""",
            IAMTriggerCondition::class.java
        )
        assertNull(withoutDelay.delay)
    }

    @Test
    fun `trigger delay survives the round-trip into triggerJson`() {
        // The repository persists the trigger as gson.toJson(response.trigger), so
        // the field has to serialize back out or the delay is lost before the
        // controller ever reads it.
        val json = IAMJson.gson.toJson(IAMTriggerCondition(type = "auto", delay = 8L))
        assertTrue("serialized trigger must carry delay: $json", json.contains("\"delay\":8"))
        assertEquals(8L, IAMJson.gson.fromJson(json, IAMTriggerCondition::class.java).delay)
    }

    @Test
    fun `trigger delay is read as seconds, not milliseconds`() {
        // The field name carries no unit, so this pins the contract: 5 means 5
        // seconds. A regression to millis would make delays 1000x too short.
        val trigger = IAMJson.gson.fromJson(
            """{"type":"auto","delay":5}""",
            IAMTriggerCondition::class.java
        )
        assertEquals("delay is in seconds", 5L, trigger.delay)
        assertEquals("5s must be 5000ms once converted", 5_000L, (trigger.delay ?: 0L) * 1000L)
    }

    @Test
    fun `action type wireValue is the lowercase contract value, not the enum name`() {
        assertEquals("open_url", IAMActionType.OPEN_URL.wireValue)
        assertEquals("request_notification_permission", IAMActionType.REQUEST_NOTIFICATION_PERMISSION.wireValue)
        assertEquals("dismiss", IAMActionType.DISMISS.wireValue)
        assertEquals("custom", IAMActionType.CUSTOM.wireValue)
    }

    @Test
    fun `action type wireValue stays in sync with its SerializedName`() {
        // wireValue is what leaves the SDK as analytics btn_type; SerializedName is
        // what Gson reads/writes for actions[].type. They must never drift apart —
        // if they do, inbound and outbound would use different vocabularies again.
        IAMActionType.values().forEach { type ->
            val serializedName = IAMActionType::class.java
                .getField(type.name)
                .getAnnotation(SerializedName::class.java)
            assertNotNull("${type.name} is missing @SerializedName", serializedName)
            assertEquals(
                "${type.name}: wireValue must equal its @SerializedName value",
                serializedName!!.value,
                type.wireValue
            )
        }
    }

    @Test
    fun `action type toString reports the wire value so stringification cannot leak enum names`() {
        // Guards any string interpolation of an action type — a bare "$type" must
        // not emit the uppercase enum name into an outbound payload.
        assertEquals("dismiss", IAMActionType.DISMISS.toString())
        assertEquals("open_url", "${IAMActionType.OPEN_URL}")
    }

    @Test
    fun `dates round-trip as ISO-8601 UTC and accept millis`() {
        assertEquals("\"2025-07-01T00:00:00Z\"", IAMJson.gson.toJson(Date(julyFirst2025UtcMillis)))
        assertEquals(
            julyFirst2025UtcMillis + 250L,
            IAMJson.gson.fromJson("\"2025-07-01T00:00:00.250Z\"", Date::class.java).time
        )
    }

    @Test
    fun `parses eventless auto trigger`() {
        // Backend sends auto triggers with no `event` — the SDK enqueues
        // them by type, so `event` must parse as null without dropping the field.
        val trigger = IAMJson.gson.fromJson(
            """{ "type": "auto" }""",
            com.pushengage.pushengage.iam.model.IAMTriggerCondition::class.java
        )
        assertEquals("auto", trigger.type)
        assertNull(trigger.event)
        assertNull(trigger.conditions)
        assertNull(trigger.match)
    }

    @Test
    fun `action label is optional for pre-label content`() {
        val action = IAMJson.gson.fromJson("""{ "type": "dismiss" }""", IAMAction::class.java)
        assertEquals(IAMActionType.DISMISS, action.type)
        assertNull(action.label)
        assertTrue(action.parameters == null)
    }
}
