package com.pushengage.pushengage.helper

/**
 * Auto-detects which Android wrapper (if any) the host process is running so the
 * SDK User-Agent can report the integration flavor. Probes for marker classes on
 * the runtime classpath; falls back to native Android when nothing matches.
 */
internal object PEPlatformDetector {

    private val markers: List<Pair<String, String>> = listOf(
        "io.flutter.embedding.engine.FlutterEngine" to PEPlatform.FLUTTER_ANDROID,
        "com.facebook.react.ReactApplication" to PEPlatform.REACT_NATIVE_ANDROID
    )

    fun detect(): String {
        for ((className, flavor) in markers) {
            if (isClassPresent(className)) {
                return flavor
            }
        }
        return PEPlatform.ANDROID
    }

    private fun isClassPresent(name: String): Boolean = try {
        Class.forName(name, false, PEPlatformDetector::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }
}
