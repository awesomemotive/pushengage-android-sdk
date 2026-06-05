package com.pushengage.pushengage.Database

import org.junit.Assert.*
import org.junit.Test

class DatabaseEntityTest {

    // --- ClickRequestEntity Tests ---

    @Test
    fun clickRequestEntity_constructorSetsAllFields() {
        val entity = ClickRequestEntity(
            "hash123",
            "tag_value",
            "click",
            "mobile",
            "android",
            "0.0.6",
            "America/New_York"
        )

        assertEquals("hash123", entity.deviceHash)
        assertEquals("tag_value", entity.tag)
        assertEquals("click", entity.action)
        assertEquals("mobile", entity.deviceType)
        assertEquals("android", entity.device)
        assertEquals("0.0.6", entity.swv)
        assertEquals("America/New_York", entity.timezone)
    }

    @Test
    fun clickRequestEntity_gettersReturnCorrectValues() {
        val entity = ClickRequestEntity(
            "device_hash_1",
            "notification_tag",
            "action_1",
            "tablet",
            "android",
            "1.0.0",
            "UTC"
        )

        assertEquals("device_hash_1", entity.deviceHash)
        assertEquals("notification_tag", entity.tag)
        assertEquals("action_1", entity.action)
        assertEquals("tablet", entity.deviceType)
        assertEquals("android", entity.device)
        assertEquals("1.0.0", entity.swv)
        assertEquals("UTC", entity.timezone)
    }

    @Test
    fun clickRequestEntity_settersUpdateValues() {
        val entity = ClickRequestEntity(
            "hash1", "tag1", "action1", "mobile", "android", "1.0", "UTC"
        )

        entity.deviceHash = "hash2"
        entity.tag = "tag2"
        entity.action = "action2"
        entity.deviceType = "tablet"
        entity.device = "ios"
        entity.swv = "2.0"
        entity.timezone = "EST"

        assertEquals("hash2", entity.deviceHash)
        assertEquals("tag2", entity.tag)
        assertEquals("action2", entity.action)
        assertEquals("tablet", entity.deviceType)
        assertEquals("ios", entity.device)
        assertEquals("2.0", entity.swv)
        assertEquals("EST", entity.timezone)
    }

    @Test
    fun clickRequestEntity_idCanBeSet() {
        val entity = ClickRequestEntity(
            "hash", "tag", "action", "mobile", "android", "1.0", "UTC"
        )

        entity.id = 42L
        assertEquals(42L, entity.id)
    }

    // --- ChannelEntity Tests ---

    @Test
    fun channelEntity_defaultConstructor_allFieldsNull() {
        val entity = ChannelEntity()

        assertNull(entity.channelName)
        assertNull(entity.channelDescription)
        assertNull(entity.groupId)
        assertNull(entity.groupName)
        assertNull(entity.importance)
        assertNull(entity.sound)
        assertNull(entity.soundFile)
        assertNull(entity.vibration)
        assertNull(entity.vibrationPattern)
        assertNull(entity.ledColor)
        assertNull(entity.ledColorCode)
        assertNull(entity.accentColor)
        assertNull(entity.badges)
        assertNull(entity.lockScreen)
    }

    @Test
    fun channelEntity_settersAndGetters_roundTrip() {
        val entity = ChannelEntity()

        entity.channelId = "channel_1"
        entity.channelName = "Test Channel"
        entity.channelDescription = "A test channel"
        entity.groupId = "group_1"
        entity.groupName = "Test Group"
        entity.importance = "IMPORTANCE_HIGH"
        entity.sound = "CUSTOM"
        entity.soundFile = "notification_sound"
        entity.vibration = "CUSTOM"
        entity.vibrationPattern = "[100, 50, 100]"
        entity.ledColor = "CUSTOM"
        entity.ledColorCode = "FF0000"
        entity.accentColor = "00FF00"
        entity.badges = true
        entity.lockScreen = "VISIBILITY_PUBLIC"

        assertEquals("channel_1", entity.channelId)
        assertEquals("Test Channel", entity.channelName)
        assertEquals("A test channel", entity.channelDescription)
        assertEquals("group_1", entity.groupId)
        assertEquals("Test Group", entity.groupName)
        assertEquals("IMPORTANCE_HIGH", entity.importance)
        assertEquals("CUSTOM", entity.sound)
        assertEquals("notification_sound", entity.soundFile)
        assertEquals("CUSTOM", entity.vibration)
        assertEquals("[100, 50, 100]", entity.vibrationPattern)
        assertEquals("CUSTOM", entity.ledColor)
        assertEquals("FF0000", entity.ledColorCode)
        assertEquals("00FF00", entity.accentColor)
        assertTrue(entity.badges)
        assertEquals("VISIBILITY_PUBLIC", entity.lockScreen)
    }

    @Test
    fun channelEntity_fullConstructor_setsAllFields() {
        val entity = ChannelEntity(
            "ch_id", "ch_name", "ch_desc",
            "grp_id", "grp_name",
            "IMPORTANCE_DEFAULT",
            "DEFAULT", "sound_file",
            "DEFAULT", "[0, 100]",
            "OFF", "000000",
            "FFFFFF",
            false, "VISIBILITY_PRIVATE"
        )

        assertEquals("ch_id", entity.channelId)
        assertEquals("ch_name", entity.channelName)
        assertEquals("ch_desc", entity.channelDescription)
        assertEquals("grp_id", entity.groupId)
        assertEquals("grp_name", entity.groupName)
        assertEquals("IMPORTANCE_DEFAULT", entity.importance)
        assertEquals("DEFAULT", entity.sound)
        assertEquals("sound_file", entity.soundFile)
        assertEquals("DEFAULT", entity.vibration)
        assertEquals("[0, 100]", entity.vibrationPattern)
        assertEquals("OFF", entity.ledColor)
        assertEquals("000000", entity.ledColorCode)
        assertEquals("FFFFFF", entity.accentColor)
        assertFalse(entity.badges)
        assertEquals("VISIBILITY_PRIVATE", entity.lockScreen)
    }

    @Test
    fun channelEntity_channelIdIsPrimaryKey() {
        val entity1 = ChannelEntity()
        entity1.channelId = "pk_1"

        val entity2 = ChannelEntity()
        entity2.channelId = "pk_2"

        assertNotEquals(entity1.channelId, entity2.channelId)
    }
}
