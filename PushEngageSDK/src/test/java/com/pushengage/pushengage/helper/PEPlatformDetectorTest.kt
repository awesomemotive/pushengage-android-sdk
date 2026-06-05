package com.pushengage.pushengage.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-test the wrapper-flavor detector. The classpath under test (`PushEngageSDK`
 * unit tests) has no Flutter/React Native markers, so the detector should fall
 * back to the native Android flavor. Positive-detection paths are integration-only
 * (would require synthesizing wrapper classes on the classpath) and are not
 * exercised here.
 */
class PEPlatformDetectorTest {

    @Test
    fun detect_returnsAndroid_whenNoWrapperOnClasspath() {
        assertEquals(PEPlatform.ANDROID, PEPlatformDetector.detect())
    }
}
