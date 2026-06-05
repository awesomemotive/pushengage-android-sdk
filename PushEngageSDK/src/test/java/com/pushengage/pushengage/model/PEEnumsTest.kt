package com.pushengage.pushengage.model

import org.junit.Assert.*
import org.junit.Test

class PEEnumsTest {

    // --- PENotificationPriority Tests ---

    @Test
    fun notificationPriority_highValue_isLowercase() {
        assertEquals("high", PENotificationPriority.HIGH.priority)
    }

    @Test
    fun notificationPriority_minValue_isLowercase() {
        assertEquals("min", PENotificationPriority.MIN.priority)
    }

    @Test
    fun notificationPriority_hasTwoValues() {
        assertEquals(2, PENotificationPriority.values().size)
    }

    // --- PENotificationVisibility Tests ---

    @Test
    fun notificationVisibility_privateValue_hasPrefix() {
        assertEquals("VISIBILITY_PRIVATE", PENotificationVisibility.PRIVATE.visibility)
    }

    @Test
    fun notificationVisibility_publicValue_hasPrefix() {
        assertEquals("VISIBILITY_PUBLIC", PENotificationVisibility.PUBLIC.visibility)
    }

    @Test
    fun notificationVisibility_secretValue_hasPrefix() {
        assertEquals("VISIBILITY_SECRET", PENotificationVisibility.SECRET.visibility)
    }

    @Test
    fun notificationVisibility_hasThreeValues() {
        assertEquals(3, PENotificationVisibility.values().size)
    }

    // --- PEChannelImportance Tests ---

    @Test
    fun channelImportance_allValues_haveImportancePrefix() {
        for (importance in PEChannelImportance.values()) {
            assertTrue(
                "Importance value should have IMPORTANCE_ prefix: ${importance.importance}",
                importance.importance.startsWith("IMPORTANCE_")
            )
        }
    }

    @Test
    fun channelImportance_hasAllFourLevels() {
        assertEquals(4, PEChannelImportance.values().size)
        assertNotNull(PEChannelImportance.HIGH)
        assertNotNull(PEChannelImportance.DEFAULT)
        assertNotNull(PEChannelImportance.LOW)
        assertNotNull(PEChannelImportance.MIN)
    }

    @Test
    fun channelImportance_highValue() {
        assertEquals("IMPORTANCE_HIGH", PEChannelImportance.HIGH.importance)
    }

    @Test
    fun channelImportance_defaultValue() {
        assertEquals("IMPORTANCE_DEFAULT", PEChannelImportance.DEFAULT.importance)
    }

    @Test
    fun channelImportance_lowValue() {
        assertEquals("IMPORTANCE_LOW", PEChannelImportance.LOW.importance)
    }

    @Test
    fun channelImportance_minValue() {
        assertEquals("IMPORTANCE_MIN", PEChannelImportance.MIN.importance)
    }

    // --- Cross-enum uniqueness ---

    @Test
    fun allVisibilityValues_areUnique() {
        val values = PENotificationVisibility.values().map { it.visibility }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun allImportanceValues_areUnique() {
        val values = PEChannelImportance.values().map { it.importance }
        assertEquals(values.size, values.toSet().size)
    }
}
