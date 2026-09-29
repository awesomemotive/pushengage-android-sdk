package com.pushengage.pushengage.iam.controller;

import androidx.annotation.RestrictTo;

import android.app.Activity;
import android.content.Context;

import com.pushengage.pushengage.iam.model.IAMMessageResponse;
import com.pushengage.pushengage.helper.PEPrefs;

import java.util.List;
import java.util.Map;

/**
 * Interface for in-app messaging controller
 * The controller is the main entry point for the in-app messaging system
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface IAMController {

    /**
     * Initialize the IAM controller with the provided context and preferences
     * object.
     * 
     * @param context The application context
     * @param prefs   The PEPrefs object to use for storing preferences
     */
    void initialize(Context context, PEPrefs prefs);

    /**
     * Process a trigger event to potentially display in-app messages
     * 
     * @param triggerEvent Event name
     */
    void processTrigger(String triggerEvent);

    /**
     * Process a trigger event with parameters to potentially display in-app
     * messages
     * 
     * @param triggerEvent Event name
     * @param parameters   Map of parameters
     */
    void processTrigger(String triggerEvent, Map<String, String> parameters);

    /**
     * Save new messages from API
     * 
     * @param messages List of message responses
     */
    void saveMessages(List<IAMMessageResponse> messages);

    /**
     * Check if there are any active messages
     * 
     * @return true if there are active messages, false otherwise
     */
    boolean hasActiveMessages();

    /**
     * Check if the IAM controller is paused
     * 
     * @return true if paused, false otherwise
     */
    boolean isPaused();

    /**
     * Pause the IAM controller (stop processing triggers)
     */
    void pause();

    /**
     * Resume the IAM controller
     */
    void resume();

    /**
     * Display message in an activity
     * 
     * @param messageId Message ID to display
     * @param activity  Activity to display in
     * @return true if displayed, false otherwise
     */
    boolean displayMessage(String messageId, Activity activity);

    /**
     * Sets a user attribute for audience targeting
     * 
     * @param key   Property key
     * @param value Property value (should be able to convert to string)
     */
    void setUserAttribute(String key, Object value);

    /**
     * Removes a user attribute
     * 
     * @param key Property key to remove
     */
    void removeUserAttribute(String key);

    /**
     * Clears all user attributes
     */
    void clearUserAttributes();

    /**
     * Manually syncs in-app messages with the server
     * 
     * @param callback Optional callback to be notified of sync completion
     */
    void syncMessages(SyncCallback callback);

    /**
     * Forces an immediate sync of analytics data to the server
     */
    void syncAnalytics();

    /**
     * Shuts down the IAM controller
     * Called when the SDK is being shut down
     */
    void shutdown();
}

/**
 * Interface for callbacks on sync operations
 */
interface SyncCallback {
    /**
     * Called when sync completes
     * 
     * @param success Whether the sync was successful
     */
    void onComplete(boolean success);
}
