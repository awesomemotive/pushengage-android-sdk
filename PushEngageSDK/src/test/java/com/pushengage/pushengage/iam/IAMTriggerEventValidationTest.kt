package com.pushengage.pushengage.iam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.PushEngageTestSupport
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Input validation for `PushEngage.triggerIAMEvent`.
 *
 * A blank event name matches no campaign, so before this guard the call reported
 * success and a typo'd event name failed silently. The code and message are pinned
 * because they are shared across every SDK — the iOS SDK and the React Native /
 * Flutter bridges all report this failure identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMTriggerEventValidationTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        originalContext = getStaticField("context")
        originalPrefs = getStaticField("prefs")
        // Install a singleton so requireInstance() passes and the name guard is reached.
        setStaticField("context", context)
        setStaticField("prefs", PEPrefs(context))
    }

    @After
    fun tearDown() {
        setStaticField("context", originalContext)
        setStaticField("prefs", originalPrefs)
    }

    @Test
    fun `empty event name fails with 400 and the shared message`() {
        val result = triggerAndCapture("")

        assertEquals(400, result.first)
        assertEquals("Event name is required", result.second)
    }

    @Test
    fun `null event name fails with 400 and the shared message`() {
        val result = triggerAndCapture(null)

        assertEquals(400, result.first)
        assertEquals("Event name is required", result.second)
    }

    @Test
    fun `empty event name with a null callback does not crash`() {
        PushEngage.triggerIAMEvent("", null, null)
    }

    @Test
    fun `single-argument overload also rejects an empty event name`() {
        // No callback to observe, so this pins only that the guard returns early
        // instead of reaching the (uninitialized) IAM controller and throwing.
        PushEngage.triggerIAMEvent("")
    }

    /** Returns (errorCode, errorMessage) reported by the callback. */
    private fun triggerAndCapture(eventName: String?): Pair<Int?, String?> {
        var code: Int? = null
        var message: String? = null
        PushEngage.triggerIAMEvent(eventName, null, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {
                throw AssertionError("expected failure, got success")
            }

            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                code = errorCode
                message = errorMessage
            }
        })
        return code to message
    }

    private fun getStaticField(fieldName: String): Any? =
        PushEngageTestSupport.getStaticField(fieldName)

    private fun setStaticField(fieldName: String, value: Any?) =
        PushEngageTestSupport.setStaticField(fieldName, value)
}
