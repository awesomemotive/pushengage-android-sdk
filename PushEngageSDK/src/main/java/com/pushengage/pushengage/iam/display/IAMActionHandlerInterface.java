package com.pushengage.pushengage.iam.display;

import androidx.annotation.RestrictTo;

/**
 * Interface for handling actions from in-app messages
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface IAMActionHandlerInterface {
    /**
     * Handles an action triggered from an in-app message
     * 
     * @param actionId The identifier of the action
     */
    void handleAction(String actionId);
}