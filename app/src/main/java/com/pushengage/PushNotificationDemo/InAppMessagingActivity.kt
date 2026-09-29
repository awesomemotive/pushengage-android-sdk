package com.pushengage.PushNotificationDemo

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.iam.action.IAMCustomActionHandler
import org.json.JSONException
import org.json.JSONObject

/**
 * Activity for testing various types of in-app messages.
 * This allows triggering different message types and handling custom actions.
 */
class InAppMessagingActivity : AppCompatActivity(), IAMCustomActionHandler {

    companion object {
        private const val TAG = "InAppMessagingActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_in_app_messaging)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Set custom action listener for handling custom actions from in-app messages
        PushEngage.setIAMCustomActionHandler(this)

        // Setup button click listeners
        setupButtonListeners()
    }
    
    override fun onResume() {
        super.onResume()
        // The SDK now automatically tracks activities, no need to call setCurrentActivity
        Log.d(TAG, "Activity resumed")
        
        // Optionally trigger a message for testing if needed
        // Uncomment the next line to automatically show a message when the activity becomes visible
        // triggerMessage("banner", "banner_message")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Activity paused")
    }

    /**
     * Handle configuration changes such as orientation changes
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"}")
        
        // The SDK now automatically handles configuration changes
        
        // The layout will automatically adjust due to our layout-land resource folder
        
        // Note: The actual IAM display manager within the SDK handles the orientation changes
        // because we added android:configChanges in the AndroidManifest.xml
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    /**
     * Wires the event-name input, the Trigger button, and the log-DB button.
     * Campaigns are server-driven, so QA types whatever event name a campaign is
     * configured with (in the PushEngage dashboard) rather than picking a fixed
     * message type.
     */
    private fun setupButtonListeners() {
        val eventInput = findViewById<EditText>(R.id.etEventName)
        val paramsInput = findViewById<EditText>(R.id.etEventParams)

        findViewById<Button>(R.id.btnTriggerEvent).setOnClickListener {
            val eventName = eventInput.text?.toString()?.trim().orEmpty()
            if (eventName.isEmpty()) {
                Toast.makeText(this, "Enter an event name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Optional trigger parameters as a JSON object. When present, the SDK
            // matches only campaigns whose trigger.parameters are all satisfied.
            val paramsText = paramsInput.text?.toString()?.trim().orEmpty()
            val parameters: Map<String, Any>? = if (paramsText.isEmpty()) {
                null
            } else {
                try {
                    jsonToMap(JSONObject(paramsText))
                } catch (e: JSONException) {
                    Toast.makeText(
                        this,
                        "Invalid JSON parameters: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
            }

            triggerMessage(eventName, parameters)
        }
    }

    /**
     * Converts a flat JSON object into a Map for the trigger-parameters argument.
     * Values are kept as their JSON types (String/Number/Boolean).
     */
    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = HashMap<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.get(key)
        }
        return map
    }

    /**
     * Triggers an in-app message for the given event name (and optional trigger
     * parameters). A message displays only if a synced campaign is configured
     * for that event, its trigger parameters are satisfied, and it is eligible.
     */
    private fun triggerMessage(eventName: String, parameters: Map<String, Any>? = null) {
        Log.d(TAG, "Triggering in-app event: $eventName, params: $parameters")

        PushEngage.triggerIAMEvent(eventName, parameters, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {
                Log.d(TAG, "Triggered event '$eventName', response: $responseObject")
                runOnUiThread {
                    Toast.makeText(
                        this@InAppMessagingActivity,
                        "Triggered '$eventName'",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                Log.e(TAG, "Failed to trigger '$eventName': $errorMessage (code: $errorCode)")
                runOnUiThread {
                    Toast.makeText(
                        this@InAppMessagingActivity,
                        "Failed to trigger '$eventName': $errorMessage",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    /**
     * Handles custom actions from in-app messages
     *
     * @param actionId The identifier of the custom action
     * @param parameters Additional parameters associated with the action
     */
    override fun onCustomAction(actionId: String, parameters: Map<String, Any>) {
        Log.d(TAG, "Custom action received: $actionId with parameters: $parameters")

        try {
            var message = "Custom action: $actionId"
            if (parameters.containsKey("action")) {
                message += "\nAction: ${parameters["action"]}"
            }

            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling custom action", e)
        }
    }
} 