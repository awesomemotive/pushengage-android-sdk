package com.pushengage.pushengage.model.request

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RequestModelSerializationTest {

    private lateinit var gson: Gson

    @Before
    fun setUp() {
        gson = GsonBuilder().create()
    }

    // --- GoalRequest Tests ---

    @Test
    fun goalRequest_serializesWithCorrectJsonKeys() {
        val request = GoalRequest(
            siteId = 12345L,
            deviceTokenHash = "abc123hash",
            name = "purchase",
            count = 5,
            value = 99.99
        )

        val json = gson.toJson(request)
        val jsonObj = JsonParser.parseString(json).asJsonObject

        assertTrue(jsonObj.has("site_id"))
        assertTrue(jsonObj.has("device_token_hash"))
        assertTrue(jsonObj.has("name"))
        assertTrue(jsonObj.has("count"))
        assertTrue(jsonObj.has("value"))

        assertEquals(12345L, jsonObj.get("site_id").asLong)
        assertEquals("abc123hash", jsonObj.get("device_token_hash").asString)
        assertEquals("purchase", jsonObj.get("name").asString)
        assertEquals(5, jsonObj.get("count").asInt)
        assertEquals(99.99, jsonObj.get("value").asDouble, 0.001)
    }

    @Test
    fun goalRequest_nullOptionalFields_omittedFromJson() {
        val request = GoalRequest(
            siteId = 1L,
            deviceTokenHash = "hash",
            name = "goal",
            count = null,
            value = null
        )

        val json = gson.toJson(request)
        val jsonObj = JsonParser.parseString(json).asJsonObject

        assertEquals("goal", jsonObj.get("name").asString)
        // Gson omits null fields by default (no serializeNulls)
        assertFalse(jsonObj.has("count"))
        assertFalse(jsonObj.has("value"))
    }

    // --- TriggerCampaignRequest Tests ---

    @Test
    fun triggerCampaignRequest_serializesAllFields() {
        val request = TriggerCampaignRequest(
            siteId = 100L,
            deviceTokenHash = "token_hash",
            campaignName = "campaign1",
            eventName = "event1",
            timezone = "America/New_York",
            referenceId = "ref_123",
            profileId = "profile_456",
            data = mapOf("key1" to "value1", "key2" to "value2")
        )

        val json = gson.toJson(request)
        val jsonObj = JsonParser.parseString(json).asJsonObject

        assertEquals(100L, jsonObj.get("site_id").asLong)
        assertEquals("token_hash", jsonObj.get("device_token_hash").asString)
        assertEquals("campaign1", jsonObj.get("campaign_name").asString)
        assertEquals("event1", jsonObj.get("event_name").asString)
        assertEquals("America/New_York", jsonObj.get("timezone").asString)
        assertEquals("ref_123", jsonObj.get("ref_id").asString)
        assertEquals("profile_456", jsonObj.get("profile_id").asString)
        assertTrue(jsonObj.has("data"))
    }

    @Test
    fun triggerCampaignRequestModel_wrapsWithPartitionKey() {
        val innerRequest = TriggerCampaignRequest(
            siteId = 1L,
            deviceTokenHash = "hash",
            campaignName = "camp",
            eventName = "event",
            timezone = "UTC"
        )
        val model = TriggerCampaignRequestModel(
            partitionKey = "partition_key_value",
            data = innerRequest
        )

        val json = gson.toJson(model)
        val jsonObj = JsonParser.parseString(json).asJsonObject

        assertEquals("partition_key_value", jsonObj.get("PartitionKey").asString)
        assertTrue(jsonObj.has("Data"))
        val dataObj = jsonObj.getAsJsonObject("Data")
        assertEquals("camp", dataObj.get("campaign_name").asString)
    }

    @Test
    fun triggerCampaignResponse_deserializesFromJson() {
        val json = """
        {
            "SequenceNumber": "12345",
            "ShardId": "shard-000"
        }
        """.trimIndent()

        val response = gson.fromJson(json, TriggerCampaignResponse::class.java)

        assertEquals("12345", response.sequenceNumber)
        assertEquals("shard-000", response.shardID)
    }

    // --- Data class equality tests ---

    @Test
    fun goal_dataClass_equalityAndCopy() {
        val goal1 = Goal(name = "purchase", count = 3, value = 49.99)
        val goal2 = Goal(name = "purchase", count = 3, value = 49.99)
        val goal3 = goal1.copy(count = 5)

        assertEquals(goal1, goal2)
        assertEquals(goal1.hashCode(), goal2.hashCode())
        assertNotEquals(goal1, goal3)
        assertEquals(5, goal3.count)
        assertEquals("purchase", goal3.name)
    }

    @Test
    fun triggerCampaign_dataClass_equalityAndCopy() {
        val tc1 = TriggerCampaign(campaignName = "camp", eventName = "event")
        val tc2 = TriggerCampaign(campaignName = "camp", eventName = "event")
        val tc3 = tc1.copy(eventName = "other_event")

        assertEquals(tc1, tc2)
        assertEquals(tc1.hashCode(), tc2.hashCode())
        assertNotEquals(tc1, tc3)
        assertEquals("camp", tc3.campaignName)
        assertEquals("other_event", tc3.eventName)
    }

    @Test
    fun triggerAlert_dataClass_allFieldsAccessible() {
        val alert = TriggerAlert(
            type = com.pushengage.pushengage.PushEngage.TriggerAlertType.priceDrop,
            productId = "prod_123",
            link = "https://example.com/product",
            price = 29.99,
            variantId = "var_1",
            alertPrice = 19.99,
            profileId = "profile_1",
            mrp = 39.99,
            data = mapOf("color" to "red")
        )

        assertEquals(com.pushengage.pushengage.PushEngage.TriggerAlertType.priceDrop, alert.type)
        assertEquals("prod_123", alert.productId)
        assertEquals("https://example.com/product", alert.link)
        assertEquals(29.99, alert.price, 0.001)
        assertEquals("var_1", alert.variantId)
        assertEquals(19.99, alert.alertPrice!!, 0.001)
        assertEquals("profile_1", alert.profileId)
        assertEquals(39.99, alert.mrp!!, 0.001)
        assertEquals(mapOf("color" to "red"), alert.data)
    }

    @Test
    fun triggerAlert_optionalFieldsDefaultToNull() {
        val alert = TriggerAlert(
            type = com.pushengage.pushengage.PushEngage.TriggerAlertType.inventory,
            productId = "prod_1",
            link = "https://example.com",
            price = 10.0
        )

        assertNull(alert.variantId)
        assertNull(alert.expiryTimestamp)
        assertNull(alert.alertPrice)
        assertNull(alert.availability)
        assertNull(alert.profileId)
        assertNull(alert.mrp)
        assertNull(alert.data)
    }

    // --- TrackEvent / TrackEventRequest Tests ---

    @Test
    fun trackEventRequest_serializesWithSnakeCaseKeys() {
        val request = TrackEventRequest(
            siteId = 1234L,
            deviceTokenHash = "hash_abc",
            eventName = "MySite.AddToCart",
            provider = "PushEngage",
            eventType = "PushEngage.CustomEvent.Send",
            profileId = "user_1",
            data = mapOf("product_id" to "p123", "qty" to 2)
        )

        val json = gson.toJson(request)
        val jsonObj = JsonParser.parseString(json).asJsonObject

        // snake_case mapping driven by @SerializedName
        assertEquals(1234L, jsonObj.get("site_id").asLong)
        assertEquals("hash_abc", jsonObj.get("device_token_hash").asString)
        assertEquals("MySite.AddToCart", jsonObj.get("event_name").asString)
        assertEquals("PushEngage", jsonObj.get("provider").asString)
        assertEquals("PushEngage.CustomEvent.Send", jsonObj.get("event_type").asString)
        assertEquals("user_1", jsonObj.get("profile_id").asString)
        val dataObj = jsonObj.getAsJsonObject("data")
        assertEquals("p123", dataObj.get("product_id").asString)
        assertEquals(2, dataObj.get("qty").asInt)
    }

    @Test
    fun trackEventRequest_nullProfileId_omittedFromJson() {
        val request = TrackEventRequest(
            siteId = 1L,
            deviceTokenHash = "h",
            eventName = "evt",
            provider = "PushEngage",
            eventType = "PushEngage.CustomEvent.Send",
            profileId = null,
            data = emptyMap()
        )
        val jsonObj = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertFalse("null profile_id should be omitted", jsonObj.has("profile_id"))
        // empty `data` is still emitted as `{}` (not null)
        assertTrue(jsonObj.has("data"))
        assertEquals(0, jsonObj.getAsJsonObject("data").size())
    }

    @Test
    fun trackEvent_publicDataClass_eventNameAccessibleAndOptionalsDefaultToNull() {
        val event = TrackEvent("evt_name")
        assertEquals("evt_name", event.eventName)
        assertNull(event.provider)
        assertNull(event.eventType)
        assertNull(event.profileId)
        assertNull(event.data)
    }

    @Test
    fun trackEvent_publicDataClass_supportsAllFields() {
        val event = TrackEvent(
            eventName = "MySite.Purchase",
            provider = "Custom",
            eventType = "PushEngage.CustomEvent.Send",
            profileId = "u_42",
            data = mapOf("amount" to 99.5, "currency" to "USD")
        )
        assertEquals("MySite.Purchase", event.eventName)
        assertEquals("Custom", event.provider)
        assertEquals("PushEngage.CustomEvent.Send", event.eventType)
        assertEquals("u_42", event.profileId)
        assertEquals(99.5, event.data!!["amount"])
        assertEquals("USD", event.data!!["currency"])
    }

    @Test
    fun trackEvent_dataClass_equalityAndCopy() {
        val a = TrackEvent("e", mapOf("k" to "v"), profileId = "id", provider = "p", eventType = "t")
        val b = TrackEvent("e", mapOf("k" to "v"), profileId = "id", provider = "p", eventType = "t")
        assertEquals(a, b)

        val c = a.copy(eventName = "different")
        assertNotEquals(a, c)
        assertEquals("different", c.eventName)
    }
}
