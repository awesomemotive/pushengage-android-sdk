package com.pushengage.pushengage

import android.content.Context
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.helper.PEPrefs
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test helpers for poking at PushEngage state. Originally these manipulated static
 * fields; after the Stage 2 migration most SDK state lives on the singleton instance,
 * so these helpers transparently route reads/writes through the singleton (auto-
 * installing a stub via the package-private test-seam constructor when needed).
 * Existing tests keep their "setStaticField('context', x)" style without changes.
 */
internal object PushEngageTestSupport {

    fun getStaticField(name: String): Any? {
        val field = PushEngage::class.java.getDeclaredField(name)
        field.isAccessible = true
        val raw = if (Modifier.isStatic(field.modifiers)) {
            field.get(null)
        } else {
            val singleton = singletonOrNull() ?: return null
            field.get(singleton)
        }
        // AtomicBoolean wraps the old Boolean flag; unwrap so legacy assertions keep working.
        return if (raw is AtomicBoolean) raw.get() else raw
    }

    fun setStaticField(name: String, value: Any?) {
        val field = PushEngage::class.java.getDeclaredField(name)
        field.isAccessible = true
        if (Modifier.isStatic(field.modifiers)) {
            // AtomicBoolean fields are final; mutate in place rather than replacing.
            if (AtomicBoolean::class.java.isAssignableFrom(field.type)) {
                (field.get(null) as AtomicBoolean).set((value as? Boolean) ?: false)
                return
            }
            field.set(null, value)
            return
        }
        // Instance field — ensure a singleton exists, then write to it.
        val singleton = singletonOrInstall()
        if (AtomicBoolean::class.java.isAssignableFrom(field.type)) {
            (field.get(singleton) as AtomicBoolean).set((value as? Boolean) ?: false)
            return
        }
        field.set(singleton, value)
    }

    fun resetSingleton() {
        val instField = PushEngage::class.java.getDeclaredField("instance")
        instField.isAccessible = true
        instField.set(null, null)
    }

    private fun singletonOrNull(): Any? {
        val instField = PushEngage::class.java.getDeclaredField("instance")
        instField.isAccessible = true
        return instField.get(null)
    }

    private fun singletonOrInstall(): Any {
        singletonOrNull()?.let { return it }
        val ctor = PushEngage::class.java.getDeclaredConstructor(
            Context::class.java, PEPrefs::class.java, PEManagerType::class.java
        )
        ctor.isAccessible = true
        val stub = ctor.newInstance(null, null, null)
        val instField = PushEngage::class.java.getDeclaredField("instance")
        instField.isAccessible = true
        instField.set(null, stub)
        return stub
    }

    /**
     * A simple recording callback. Synchronous in our tests because retrofit calls either
     * short-circuit (validation) or use Robolectric's main-thread executor.
     */
    class RecordingCallback : PushEngageResponseCallback {
        var successInvoked: Boolean = false
        var failureInvoked: Boolean = false
        var successObject: Any? = null
        var failureCode: Int? = null
        var failureMessage: String? = null

        override fun onSuccess(responseObject: Any?) {
            successInvoked = true
            successObject = responseObject
        }

        override fun onFailure(errorCode: Int?, errorMessage: String?) {
            failureInvoked = true
            failureCode = errorCode
            failureMessage = errorMessage
        }
    }
}
