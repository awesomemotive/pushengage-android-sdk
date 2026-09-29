package com.pushengage.pushengage.iam.action

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.iam.model.IAMAction
import com.pushengage.pushengage.iam.model.IAMActionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers [IAMActionHandler.handleAction] for every [IAMActionType], plus the
 * activity-level permission-result plumbing.
 *
 * Action JSON parsing (createActionFromJson) is covered separately by
 * com.pushengage.pushengage.iam.IAMActionParsingTest and is intentionally not
 * duplicated here.
 */
@RunWith(RobolectricTestRunner::class)
class IAMActionHandlerTest {

    private lateinit var activity: Activity
    private lateinit var handler: IAMActionHandler

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        handler = IAMActionHandler(activity)
    }

    // ---------------------------------------------------------------- OPEN_URL

    @Test
    fun `open_url with https url starts ACTION_VIEW intent with NEW_TASK flag and returns true`() {
        installBrowserFor("https://pushengage.com/promo")
        val action = urlAction("https://pushengage.com/promo")

        val handled = handler.handleAction(action, "btn-1")

        assertTrue(handled)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("An ACTION_VIEW intent should have been started", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://pushengage.com/promo"), started.data)
        assertTrue(
            "Intent should carry FLAG_ACTIVITY_NEW_TASK",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun `open_url with http url is allowed`() {
        installBrowserFor("http://pushengage.com")

        assertTrue(handler.handleAction(urlAction("http://pushengage.com"), "btn-1"))
        assertEquals(Uri.parse("http://pushengage.com"), shadowOf(activity).nextStartedActivity.data)
    }

    @Test
    fun `open_url scheme check is case-insensitive`() {
        installBrowserFor("HTTPS://pushengage.com")

        assertTrue(handler.handleAction(urlAction("HTTPS://pushengage.com"), "btn-1"))
        assertEquals(Uri.parse("HTTPS://pushengage.com"), shadowOf(activity).nextStartedActivity.data)
    }

    @Test
    fun `open_url without scheme gets https prefixed`() {
        installBrowserFor("https://example.com/x")

        val handled = handler.handleAction(urlAction("example.com/x"), "btn-1")

        assertTrue(handled)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Uri.parse("https://example.com/x"), started.data)
    }

    @Test
    fun `open_url with javascript scheme is rejected without starting an intent`() {
        val handled = handler.handleAction(urlAction("javascript:alert(1)"), "btn-1")

        assertFalse(handled)
        assertNull("No intent must be started for a javascript: url", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `open_url with file scheme is rejected without starting an intent`() {
        val handled = handler.handleAction(urlAction("file:///etc/passwd"), "btn-1")

        assertFalse(handled)
        assertNull("No intent must be started for a file: url", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `open_url with any non-http scheme is rejected`() {
        assertFalse(handler.handleAction(urlAction("mailto:someone@example.com"), "btn-1"))
        assertFalse(handler.handleAction(urlAction("intent://scan/#Intent;end"), "btn-1"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `open_url with null parameters returns false and starts no intent`() {
        val action = IAMAction(IAMActionType.OPEN_URL, null)

        assertFalse(handler.handleAction(action, "btn-1"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `open_url with parameters missing the url key returns false`() {
        val action = IAMAction(IAMActionType.OPEN_URL, mapOf("link" to "https://pushengage.com"))

        assertFalse(handler.handleAction(action, "btn-1"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `open_url with empty url returns false`() {
        assertFalse(handler.handleAction(urlAction(""), "btn-1"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `open_url with no registered ResolveInfo still launches the intent`() {
        // There is deliberately no resolveActivity() pre-check: from Android 11
        // package-visibility filtering makes it return null even when a browser
        // exists, so the handler always attempts the launch.
        val handled = handler.handleAction(urlAction("https://pushengage.com"), "btn-1")

        assertTrue(handled)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://pushengage.com"), started.data)
    }

    @Test
    fun `open_url returns false when nothing on the device can handle the intent`() {
        // With activity checking on, Robolectric throws ActivityNotFoundException for
        // an intent no installed activity matches — the real no-browser case. This is
        // now the only guard, so it must not crash and must report failure.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .checkActivities(true)

        val handled = handler.handleAction(urlAction("https://pushengage.com"), "btn-1")

        assertFalse(handled)
    }

    // ------------------------------------------------------------------ CUSTOM

    @Test
    fun `custom action with registered listener passes actionId and parameters through`() {
        val listener = RecordingCustomActionHandler()
        handler.setCustomActionHandler(listener)
        val action = IAMAction(IAMActionType.CUSTOM, mapOf("key" to "value", "count" to 3))

        val handled = handler.handleAction(action, "custom-42")

        assertTrue(handled)
        assertEquals("custom-42", listener.lastActionId)
        // Scalars are delivered as text — the cross-platform contract, matching
        // iOS's [String: String] parameters. Keys and values are otherwise the
        // campaign's own.
        assertEquals(mapOf("key" to "value", "count" to "3"), listener.lastParameters)
    }

    @Test
    fun `a JSON number parameter is delivered without a decimal point`() {
        // The exact path the RN and Flutter bridges take: campaign JSON -> Gson ->
        // listener -> value.toString(). Gson decodes every JSON number as a Double,
        // so {"discount": 20} used to reach JS as "20.0" while iOS delivered "20".
        val listener = RecordingCustomActionHandler()
        handler.setCustomActionHandler(listener)
        val action = IAMActionHandler.createActionFromJson(
            JSONObject("""{"type":"custom","parameters":{"discount":20,"rate":2.5,"code":"SAVE20"}}""")
        )

        val handled = handler.handleAction(action!!, "apply_discount")

        assertTrue(handled)
        assertEquals("20", listener.lastParameters?.get("discount")?.toString())
        assertEquals("2.5", listener.lastParameters?.get("rate")?.toString())
        assertEquals("SAVE20", listener.lastParameters?.get("code")?.toString())
    }

    @Test
    fun `custom action with null parameters delivers an empty map`() {
        val listener = RecordingCustomActionHandler()
        handler.setCustomActionHandler(listener)

        val handled = handler.handleAction(IAMAction(IAMActionType.CUSTOM, null), "custom-1")

        assertTrue(handled)
        assertEquals("custom-1", listener.lastActionId)
        assertEquals(emptyMap<String, Any>(), listener.lastParameters)
    }

    @Test
    fun `custom action without a listener returns false`() {
        assertFalse(handler.handleAction(IAMAction(IAMActionType.CUSTOM, mapOf("k" to "v")), "custom-1"))
    }

    @Test
    fun `custom action returns false when the listener throws`() {
        handler.setCustomActionHandler(object : IAMCustomActionHandler {
            override fun onCustomAction(actionId: String, parameters: Map<String, Any>) {
                throw IllegalStateException("listener blew up")
            }
        })

        assertFalse(handler.handleAction(IAMAction(IAMActionType.CUSTOM, null), "custom-1"))
    }

    // ----------------------------------------------------------------- DISMISS

    @Test
    fun `dismiss returns true and starts no intent`() {
        assertTrue(handler.handleAction(IAMAction(IAMActionType.DISMISS, null), "close"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // ---------------------------------------- REQUEST_NOTIFICATION_PERMISSION

    @Test
    @Config(sdk = [28])
    fun `notification permission on pre-33 succeeds without requesting anything`() {
        val handled = handler.handleAction(
            IAMAction(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, null), "perm"
        )

        assertTrue(handled)
        assertNull(
            "No runtime permission request expected below API 33",
            shadowOf(activity).lastRequestedPermission
        )
    }

    @Test
    @Config(sdk = [33])
    fun `notification permission on 33 uses the SDK's self-contained flow, not a host-level request`() {
        // Robolectric does not auto-grant POST_NOTIFICATIONS on SDK 33.
        // A direct ActivityCompat.requestPermissions against the HOST activity
        // delivers the result to the host's onRequestPermissionsResult — which
        // silently goes nowhere unless the app forwards it. The SDK must use
        // its own self-contained mechanism instead: for a plain Activity, the
        // invisible PEPermissionHelperActivity.
        val handled = handler.handleAction(
            IAMAction(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, null), "perm"
        )

        assertTrue(handled)
        assertNull(
            "the host activity must not receive a raw permission request it has to forward",
            shadowOf(activity).lastRequestedPermission
        )
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("the invisible permission helper activity should be launched", started)
        assertEquals(
            com.pushengage.pushengage.permissionhandling.PEPermissionHelperActivity::class.java.name,
            started.component?.className
        )
    }

    @Test
    @Config(sdk = [33])
    fun `notification permission on a FragmentActivity host attaches the SDK's permission fragment`() {
        val fragmentActivity =
            Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup().get()
        val fragmentHandler = IAMActionHandler(fragmentActivity)

        val handled = fragmentHandler.handleAction(
            IAMAction(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, null), "perm"
        )

        assertTrue(handled)
        assertNotNull(
            "the SDK's own result-receiving fragment must be attached",
            fragmentActivity.supportFragmentManager.findFragmentByTag("PEPermissionFragment")
        )
    }

    @Test
    @Config(sdk = [33])
    fun `grant result from the SDK flow is relayed to the IAM permission callback`() {
        val fragmentActivity =
            Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup().get()
        val fragmentHandler = IAMActionHandler(fragmentActivity)
        val callback = RecordingPermissionResultCallback()
        fragmentHandler.setPermissionResultCallback(callback)

        fragmentHandler.handleAction(
            IAMAction(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, null), "perm"
        )
        val fragment = fragmentActivity.supportFragmentManager.findFragmentByTag("PEPermissionFragment")
        assertNotNull(fragment)

        // The system dialog resolves: the SDK fragment receives the result itself.
        fragment!!.onRequestPermissionsResult(
            200, // PEPermissionFragment's internal request code
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )

        assertTrue("result must reach the IAM callback without host forwarding", callback.invoked)
        assertTrue(callback.lastGranted)
        assertEquals(IAMActionHandler.NOTIFICATION_PERMISSION_REQUEST_CODE, callback.lastRequestCode)
    }

    @Test
    @Config(sdk = [33])
    fun `notification permission on 33 with grant already held returns true without requesting`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions("android.permission.POST_NOTIFICATIONS")

        val handled = handler.handleAction(
            IAMAction(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, null), "perm"
        )

        assertTrue(handled)
        assertNull(
            "Already-granted permission must not be re-requested",
            shadowOf(activity).lastRequestedPermission
        )
    }

    @Test
    @Config(sdk = [33])
    fun `denied result from the SDK flow is relayed to the IAM permission callback`() {
        val fragmentActivity =
            Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup().get()
        val fragmentHandler = IAMActionHandler(fragmentActivity)
        val callback = RecordingPermissionResultCallback()
        fragmentHandler.setPermissionResultCallback(callback)

        fragmentHandler.handleAction(
            IAMAction(IAMActionType.REQUEST_NOTIFICATION_PERMISSION, null), "perm"
        )
        val fragment = fragmentActivity.supportFragmentManager.findFragmentByTag("PEPermissionFragment")
        assertNotNull(fragment)

        fragment!!.onRequestPermissionsResult(
            200,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )

        assertTrue(callback.invoked)
        assertFalse(callback.lastGranted)
    }

    // ----------------------------------------------------------------- helpers

    private fun urlAction(url: String): IAMAction =
        IAMAction(IAMActionType.OPEN_URL, mapOf("url" to url))

    /** Registers a fake browser so Intent.resolveActivity() finds a handler for [url]. */
    private fun installBrowserFor(url: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.browser"
                name = "com.example.browser.BrowserActivity"
                applicationInfo = ApplicationInfo().apply { packageName = "com.example.browser" }
            }
        }
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            resolveInfo
        )
    }

    private class RecordingCustomActionHandler : IAMCustomActionHandler {
        var lastActionId: String? = null
        var lastParameters: Map<String, Any>? = null

        override fun onCustomAction(actionId: String, parameters: Map<String, Any>) {
            lastActionId = actionId
            lastParameters = parameters
        }
    }

    private class RecordingPermissionResultCallback : IAMPermissionResultCallback {
        var invoked = false
        var lastRequestCode = -1
        var lastGranted = false

        override fun onPermissionResult(requestCode: Int, granted: Boolean) {
            invoked = true
            lastRequestCode = requestCode
            lastGranted = granted
        }
    }
}
