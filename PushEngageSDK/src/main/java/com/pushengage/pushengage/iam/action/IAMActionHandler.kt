package com.pushengage.pushengage.iam.action

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.permissionhandling.PEPermissionFragment
import com.pushengage.pushengage.permissionhandling.PEPermissionHelperActivity
import com.pushengage.pushengage.iam.model.IAMAction
import com.pushengage.pushengage.iam.model.IAMActionType
import com.pushengage.pushengage.iam.network.IAMJson
import com.pushengage.pushengage.iam.util.IAMScalarString
import org.json.JSONObject

/**
 * Handles different types of actions for in-app messages
 */
internal class IAMActionHandler(private val activity: Activity) {

    private var customActionHandler: IAMCustomActionHandler? = null
    private var permissionResultCallback: IAMPermissionResultCallback? = null

    /**
     * Sets a handler for custom actions
     */
    fun setCustomActionHandler(handler: IAMCustomActionHandler) {
        customActionHandler = handler
    }

    /**
     * Sets a callback for permission results
     */
    fun setPermissionResultCallback(callback: IAMPermissionResultCallback) {
        PELogger.debug("IAMActionHandler: Setting permission result callback")
        permissionResultCallback = callback
    }

    /**
     * Handles an action based on its type
     */
    fun handleAction(action: IAMAction, actionId: String): Boolean {
        return when (action.type) {
            IAMActionType.OPEN_URL -> handleUrlAction(action, actionId)
            IAMActionType.REQUEST_NOTIFICATION_PERMISSION -> handleNotificationPermissionAction(actionId)
            IAMActionType.CUSTOM -> handleCustomAction(actionId, action)
            IAMActionType.DISMISS -> true // Dismiss is handled by the display manager
        }
    }

    /**
     * Handles opening URLs
     */
    private fun handleUrlAction(action: IAMAction, actionId: String): Boolean {
        try {
            val parameters = action.parameters
            if (parameters == null || !parameters.containsKey("url")) {
                PELogger.error("URL action missing url parameter")
                return false
            }

            val urlObj = parameters["url"]
            PELogger.debug("URL parameter found: $urlObj (${urlObj?.javaClass?.name})")
            
            // Extract the URL string regardless of type (handles both String and JSONObject)
            val url = urlObj?.toString() ?: ""
            if (url.isEmpty()) {
                PELogger.error("Empty URL provided")
                return false
            }
            
            PELogger.debug("Attempting to open URL: $url")

            // Validate URL
            val uri = Uri.parse(url)
            if (uri.scheme == null) {
                // If no scheme is provided, default to https
                PELogger.debug("No scheme in URL, defaulting to https")
                val updatedUrl = "https://$url"
                return openUrlWithIntent(updatedUrl, actionId)
            } else if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) {
                PELogger.error("Invalid URL scheme: ${uri.scheme}")
                return false
            }

            // Open the validated URL
            return openUrlWithIntent(url, actionId)
        } catch (e: Exception) {
            PELogger.error("Error handling URL action", e)
            return false
        }
    }

    /**
     * Opens a URL with an Intent
     */
    private fun openUrlWithIntent(url: String, actionId: String): Boolean {
        // Deliberately no resolveActivity() pre-check. From Android 11 (API 30)
        // package-visibility filtering makes resolveActivity() return null for
        // ACTION_VIEW unless the app declares a matching <queries> element, so the
        // check reported "no browser" on every modern device even when one existed.
        // Attempting the launch and catching ActivityNotFoundException is both
        // accurate and the documented approach.
        return try {
            val uri = Uri.parse(url)
            PELogger.debug("Opening URL with Intent: $url")

            // FLAG_ACTIVITY_NEW_TASK is required: the message may already be
            // dismissed, or the host may hand us a non-Activity context.
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            PELogger.error("No activity found to handle URL: $url", e)
            false
        } catch (e: Exception) {
            PELogger.error("Error opening URL with intent", e)
            false
        }
    }

    /**
     * Handles notification permission requests
     */
    private fun handleNotificationPermissionAction(actionId: String): Boolean {
        PELogger.debug("IAMActionHandler: handleNotificationPermissionAction called with actionId=$actionId")
        return try {
            if (Build.VERSION.SDK_INT >= 33) { // Android 13 (TIRAMISU) is API level 33
                val permissionState = androidx.core.content.ContextCompat.checkSelfPermission(
                    activity,
                    "android.permission.POST_NOTIFICATIONS"
                )

                if (permissionState == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // Permission is already granted
                    PELogger.debug("IAMActionHandler: Notification permission already granted")
                    return true
                }

                // Self-contained request: the SDK's own fragment (for
                // ComponentActivity/FragmentActivity hosts) or invisible helper
                // activity receives the system result ITSELF and auto-subscribes
                // on grant. A raw ActivityCompat.requestPermissions against the
                // host activity delivered the result to the host's
                // onRequestPermissionsResult, which silently went nowhere unless
                // the app remembered to forward it to the SDK.
                val relay = PushEngagePermissionCallback { granted, _ ->
                    PELogger.debug(
                        "IAMActionHandler: Notification permission ${if (granted) "granted" else "denied"}"
                    )
                    permissionResultCallback?.onPermissionResult(
                        NOTIFICATION_PERMISSION_REQUEST_CODE, granted
                    )
                }
                if (activity is androidx.activity.ComponentActivity) {
                    PEPermissionFragment.requestPermission(activity, relay)
                } else {
                    PEPermissionHelperActivity.requestPermission(activity, relay)
                }
                return true
            } else {
                // For older versions, no runtime permission needed
                PELogger.debug("IAMActionHandler: Runtime notification permission not required for this Android version")
                return true
            }
        } catch (e: Exception) {
            PELogger.error("IAMActionHandler: Error handling notification permission action", e)
            false
        }
    }

    /**
     * Handles custom actions by delegating to the handler
     */
    private fun handleCustomAction(actionId: String, action: IAMAction): Boolean {
        val handler = customActionHandler
        if (handler == null) {
            PELogger.error("No custom action handler set")
            return false
        }

        try {
            // Scalars go out as text, so the RN/Flutter bridges (which stringify
            // whatever they receive) and native consumers all see the same value
            // iOS delivers: a campaign's {"discount": 20} is "20", not Gson's
            // Double "20.0".
            val parameters = action.parameters?.let { IAMScalarString.normalizeValues(it) } ?: emptyMap()
            handler.onCustomAction(actionId, parameters)
            return true
        } catch (e: Exception) {
            PELogger.error("Error handling custom action", e)
            return false
        }
    }

    companion object {
        /**
         * Request code for notification permission
         */
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 100
        
        /**
         * Creates an IAMAction from a JSON object
         */
        @JvmStatic
        fun createActionFromJson(actionJson: JSONObject): IAMAction? {
            return try {
                // Parse via Gson so the wire values and their legacy alternates
                // declared on IAMActionType apply (a manual valueOf() would only
                // accept enum constant names).
                val action = IAMJson.gson.fromJson(actionJson.toString(), IAMAction::class.java)
                @Suppress("SENSELESS_COMPARISON")
                if (action == null || action.type == null) {
                    PELogger.error("Action JSON has missing or unknown type: $actionJson")
                    return null
                }
                action
            } catch (e: Exception) {
                PELogger.error("Error parsing action JSON", e)
                null
            }
        }
    }
} 