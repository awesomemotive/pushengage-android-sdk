package com.pushengage.pushengage.Callbacks;

/**
 * Callback interface for notification permission requests
 */
public interface PushEngagePermissionCallback {
    /**
     * Called when the permission request is completed
     * 
     * @param granted true if permission was granted, false otherwise
     * @param error   any error that occurred during the permission request process
     */
    void onPermissionResult(boolean granted, Error error);
}