package com.pushengage.pushengage.helper

/**
 * String constants for the platform-flavor segment of the SDK User-Agent. Wrapper
 * SDKs (and tests) can reference these to avoid typos, but the field accepts any
 * sanitized string — the SDK does not gatekeep new wrappers.
 */
object PEPlatform {
    const val ANDROID = "Android"
    const val FLUTTER_ANDROID = "FlutterAndroid"
    const val REACT_NATIVE_ANDROID = "ReactNativeAndroid"
}
