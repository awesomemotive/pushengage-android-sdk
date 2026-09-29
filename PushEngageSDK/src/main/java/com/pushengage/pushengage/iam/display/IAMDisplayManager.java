package com.pushengage.pushengage.iam.display;

import androidx.annotation.RestrictTo;

import android.app.Activity;

import com.pushengage.pushengage.iam.model.IAMMessage;

/**
 * Interface for the In-App Message Display Manager
 * Responsible for managing the display of in-app messages
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface IAMDisplayManager {

    /**
     * Initializes the display manager
     */
    void initialize();

    /**
     * Displays an in-app message
     *
     * @param message  The message to display
     * @param activity The activity context to display in
     * @return true if the message was displayed, false otherwise
     */
    boolean displayMessage(IAMMessage message, Activity activity);

    /**
     * Dismisses the currently displayed message (if any)
     */
    void dismissCurrentMessage();

    /**
     * Handles an action triggered from the message
     *
     * @param actionId The ID of the action
     */
    void handleAction(String actionId);

    /**
     * Pauses the display manager (e.g., when app goes to background)
     */
    void pause();

    /**
     * Resumes the display manager (e.g., when app comes to foreground)
     */
    void resume();

    /**
     * Shuts down the display manager and cleans up resources
     */
    void shutdown();
}