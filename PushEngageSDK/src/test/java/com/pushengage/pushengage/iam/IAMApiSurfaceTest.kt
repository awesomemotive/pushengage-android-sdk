package com.pushengage.pushengage.iam

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.reflect.KVisibility

/**
 * Pins the IAM public API surface. The SDK ships as an AAR: anything public
 * here becomes part of the consumer-facing API and is near-impossible to
 * retract once released. Everything in the `iam` package must therefore be
 * Kotlin `internal` (or non-public), except the deliberately public host-app
 * API — and public Java implementation classes (which have no module-internal
 * visibility) must carry `@RestrictTo(LIBRARY)` so consumer lint flags any use.
 */
class IAMApiSurfaceTest {

    /**
     * The IAM API host apps are meant to touch (referenced from README/PushEngage),
     * plus the one implementation class the wrapper SDKs must reach.
     *
     * `IAMConfigurationManager` is public only because wrapper bridges (React
     * Native, Flutter) called `onActivityResumed` directly to deliver the resume
     * they initialize too late to see. `PEActivityTracker` now handles that inside
     * the SDK; remove this entry and make the class `internal` once no bridge
     * references it. It carries `@RestrictTo(LIBRARY_GROUP)` and is NOT a
     * supported consumer API.
     */
    private val deliberatelyPublic = setOf(
        "com.pushengage.pushengage.iam.action.IAMCustomActionHandler",
        "com.pushengage.pushengage.iam.util.IAMConfigurationManager"
    )

    @Test
    fun `every IAM class is internal or RestrictTo-library except the deliberate public API`() {
        val classNames = scanIamClassNames()
        assertTrue(
            "class scan must find the IAM classes (found ${classNames.size})",
            classNames.size >= 40
        )

        val leaks = mutableListOf<String>()
        for (name in classNames) {
            if (name in deliberatelyPublic) continue
            val clazz = try {
                Class.forName(name, false, javaClass.classLoader)
            } catch (e: Throwable) {
                continue
            }
            if (clazz.isSynthetic) continue
            if (!Modifier.isPublic(clazz.modifiers)) continue // package-private: fine

            val isKotlin = clazz.getAnnotation(Metadata::class.java) != null
            if (isKotlin) {
                val visibility = try {
                    clazz.kotlin.visibility
                } catch (e: Throwable) {
                    null // file facades etc — no top-level class surface
                }
                if (visibility == KVisibility.INTERNAL || visibility == KVisibility.PRIVATE) continue
                if (visibility == null) continue
                leaks.add("$name (kotlin, $visibility)")
            } else {
                if (!classFileMentionsRestrictTo(name)) {
                    leaks.add("$name (java, public, no @RestrictTo)")
                }
            }
        }

        assertTrue(
            "IAM classes leaking into the consumer-facing API:\n" + leaks.joinToString("\n"),
            leaks.isEmpty()
        )
    }

    /**
     * @RestrictTo has CLASS retention (invisible to runtime reflection), so
     * detect it in the class-file constant pool instead.
     */
    private fun classFileMentionsRestrictTo(className: String): Boolean {
        val resource = className.replace('.', '/') + ".class"
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: return false
        return String(bytes, Charsets.ISO_8859_1).contains("androidx/annotation/RestrictTo")
    }

    /**
     * Enumerates the SDK's compiled top-level classes under the iam package.
     * Test-source class dirs (paths containing "UnitTest"/"test") are skipped —
     * test classes are not part of the shipped surface.
     */
    private fun scanIamClassNames(): List<String> {
        val basePath = "com/pushengage/pushengage/iam"
        val names = mutableListOf<String>()
        for (url in javaClass.classLoader.getResources(basePath).toList()) {
            when (url.protocol) {
                "file" -> {
                    val root = File(url.toURI())
                    if (looksLikeTestOutput(root.path)) continue
                    root.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .forEach {
                            val relative = it.relativeTo(root).path
                                .removeSuffix(".class")
                                .replace(File.separatorChar, '.')
                            names.add("com.pushengage.pushengage.iam.$relative")
                        }
                }
                "jar" -> {
                    val jarPath = url.path.substringAfter("file:").substringBefore("!")
                    if (looksLikeTestOutput(jarPath)) continue
                    JarFile(File(java.net.URLDecoder.decode(jarPath, "UTF-8"))).use { jar ->
                        jar.entries().asSequence()
                            .filter { it.name.startsWith(basePath) && it.name.endsWith(".class") }
                            .forEach {
                                names.add(it.name.removeSuffix(".class").replace('/', '.'))
                            }
                    }
                }
            }
        }
        return names
            .filter { !it.contains('$') } // nested/companion classes follow their parent
            .filter { !it.endsWith("Kt") } // file facades carry no class surface
            .filter { !it.endsWith("_Impl") } // Room-generated, cannot be annotated
            .distinct()
    }

    private fun looksLikeTestOutput(path: String): Boolean =
        path.contains("UnitTest") || path.contains("/test", ignoreCase = true)
}
