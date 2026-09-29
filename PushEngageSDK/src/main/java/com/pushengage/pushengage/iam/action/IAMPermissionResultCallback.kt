package com.pushengage.pushengage.iam.action

/**
 * Interface for handling permission request results
 */
internal interface IAMPermissionResultCallback {
    /**
     * Called when a permission request result is received
     * 
     * @param requestCode The request code used when requesting the permission
     * @param granted Whether the permission was granted
     */
    fun onPermissionResult(requestCode: Int, granted: Boolean)
} 