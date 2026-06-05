package com.pushengage.pushengage.helper

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PELoggerTest {

    @Before
    fun setUp() {
        PELogger.enableLogging(false)
    }

    @After
    fun tearDown() {
        PELogger.enableLogging(false)
    }

    @Test
    fun enableLogging_true_enablesDebugOutput() {
        PELogger.enableLogging(true)
        assertTrue(PELogger.isLoggingEnabled())
    }

    @Test
    fun enableLogging_false_disablesDebugOutput() {
        PELogger.enableLogging(true)
        PELogger.enableLogging(false)
        assertFalse(PELogger.isLoggingEnabled())
    }

    @Test
    fun debug_whenDisabled_doesNotThrow() {
        PELogger.enableLogging(false)
        PELogger.debug("test message")
    }

    @Test
    fun error_whenDisabled_doesNotThrow() {
        PELogger.enableLogging(false)
        PELogger.error("test error")
    }

    @Test
    fun error_withNullThrowable_doesNotThrow() {
        PELogger.enableLogging(true)
        PELogger.error("test error", null)
    }

    @Test
    fun debug_whenEnabled_doesNotThrow() {
        PELogger.enableLogging(true)
        PELogger.debug("test message")
    }

    @Test
    fun error_whenEnabled_withThrowable_doesNotThrow() {
        PELogger.enableLogging(true)
        PELogger.error("test error", RuntimeException("test"))
    }

    @Test
    fun defaultState_loggingIsDisabled() {
        // Reset to default
        PELogger.enableLogging(false)
        assertFalse(PELogger.isLoggingEnabled())
    }
}
