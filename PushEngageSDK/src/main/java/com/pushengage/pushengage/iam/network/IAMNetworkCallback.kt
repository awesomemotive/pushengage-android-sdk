package com.pushengage.pushengage.iam.network

/**
 * Callback interface for IAM network operations
 * @param T The type of data returned on success
 */
internal interface IAMNetworkCallback<T> {
    /**
     * Called when the network operation succeeds
     * @param data The data returned from the operation
     */
    fun onSuccess(data: T)
    
    /**
     * Called when the network operation fails
     * @param error The exception that occurred
     */
    fun onError(error: Exception)
} 