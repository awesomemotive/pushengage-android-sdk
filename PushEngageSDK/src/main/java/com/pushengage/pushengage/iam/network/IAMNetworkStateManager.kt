package com.pushengage.pushengage.iam.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.pushengage.pushengage.helper.PELogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interface for objects that want to be notified of network state changes
 */
internal interface IAMNetworkStateObserver {
    /**
     * Called when the network state changes
     * @param isConnected Whether the device is connected to the internet
     */
    fun networkStateDidChange(isConnected: Boolean)
}

/**
 * Manager for monitoring network connectivity for in-app messaging
 */
internal class IAMNetworkStateManager private constructor(context: Context) {
    
    // Store application context instead of the original context to prevent memory leaks
    private val applicationContext = context.applicationContext
    
    private val observers = CopyOnWriteArrayList<IAMNetworkStateObserver>()
    
    private var _isConnected = false
    
    /**
     * Whether the device is currently connected to the internet
     */
    val isConnected: Boolean
        get() = _isConnected
    
    init {
        // Initialize connectivity monitoring
        setupNetworkCallback()
        
        // Initialize with current connectivity status
        _isConnected = isNetworkAvailable()
    }
    
    /**
     * Adds an observer for network state changes
     * @param observer The observer to add
     */
    fun addObserver(observer: IAMNetworkStateObserver) {
        if (!observers.contains(observer)) {
            observers.add(observer)
            // Notify the new observer of the current state
            observer.networkStateDidChange(_isConnected)
        }
    }
    
    /**
     * Removes an observer
     * @param observer The observer to remove
     */
    fun removeObserver(observer: IAMNetworkStateObserver) {
        observers.remove(observer)
    }
    
    private fun setupNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use NetworkCallback for API 24+
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleConnectivityChange(true)
                }
                
                override fun onLost(network: Network) {
                    // Losing ONE network is not the same as going offline: another
                    // network may still be up, and a torn-down network's onLost can
                    // even arrive AFTER its replacement's onAvailable (seen on device
                    // after an airplane-mode cycle: onLost landed ~36 s after the new
                    // network was validated). Reporting false unconditionally latched
                    // the manager offline for the rest of the process, so nothing that
                    // gates on `isConnected` — IAM analytics flushing — ever ran again.
                    // Ask the system what is actually available instead.
                    handleConnectivityChange(isNetworkAvailable())
                }
            }
            
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } else {
            // Use BroadcastReceiver for older APIs
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    handleConnectivityChange(isNetworkAvailable())
                }
            }, filter)
        }
    }
    
    private fun handleConnectivityChange(isConnected: Boolean) {
        if (this._isConnected != isConnected) {
            this._isConnected = isConnected
            PELogger.debug("Network connectivity changed: ${if (isConnected) "CONNECTED" else "DISCONNECTED"}")
            
            // Notify observers
            observers.forEach { observer ->
                observer.networkStateDidChange(isConnected)
            }
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }
    
    companion object {
        @Volatile
        private var instance: IAMNetworkStateManager? = null
        
        fun getInstance(context: Context): IAMNetworkStateManager {
            return instance ?: synchronized(this) {
                instance ?: IAMNetworkStateManager(context.applicationContext).also { instance = it }
            }
        }
    }
} 