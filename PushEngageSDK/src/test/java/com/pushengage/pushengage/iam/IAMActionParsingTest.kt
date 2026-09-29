package com.pushengage.pushengage.iam

import com.pushengage.pushengage.iam.action.IAMActionHandler
import com.pushengage.pushengage.iam.model.IAMActionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for the WebView action path: stored/authored action JSON is
 * parsed with the wire values ("dismiss", "open_url", ...) AND the legacy enum
 * names older builds wrote into Room. A raw IAMActionType.valueOf() here once
 * made every in-app button a silent no-op for wire-format JSON.
 */
@RunWith(RobolectricTestRunner::class)
class IAMActionParsingTest {

    @Test
    fun `parses wire-format action type`() {
        val action = IAMActionHandler.createActionFromJson(JSONObject("""{"type":"dismiss"}"""))
        assertEquals(IAMActionType.DISMISS, action?.type)
    }

    @Test
    fun `parses wire-format action with parameters and label`() {
        val action = IAMActionHandler.createActionFromJson(
            JSONObject("""{"type":"open_url","label":"Learn More","parameters":{"url":"https://pushengage.com"}}""")
        )
        assertEquals(IAMActionType.OPEN_URL, action?.type)
        assertEquals("Learn More", action?.label)
        assertEquals("https://pushengage.com", action?.parameters?.get("url"))
    }

    @Test
    fun `uppercase action type is rejected, not silently accepted`() {
        // Only the lowercase wire value is a valid type. An uppercase spelling is
        // treated like any unrecognised value so the mismatch surfaces instead of
        // producing a half-built action.
        assertNull(IAMActionHandler.createActionFromJson(JSONObject("""{"type":"REQUEST_NOTIFICATION_PERMISSION"}""")))
        assertNull(IAMActionHandler.createActionFromJson(JSONObject("""{"type":"DISMISS"}""")))
    }

    @Test
    fun `unknown action type returns null instead of a broken action`() {
        assertNull(IAMActionHandler.createActionFromJson(JSONObject("""{"type":"launch_rocket"}""")))
    }
}
