package com.pushengage.pushengage.helper

import android.util.Log

internal object PELogger {
    private const val TAG = "PushEngage"
    private var isLoggingEnabled: Boolean = false
    private const val PREFIX = "PushEngage: "

    @JvmStatic
    fun isLoggingEnabled(): Boolean {
        return isLoggingEnabled
    }

    @JvmStatic
    fun enableLogging(shouldEnable: Boolean) {
        this.isLoggingEnabled = shouldEnable
    }

    @JvmStatic
    fun debug(message: String) {
        if(isLoggingEnabled) {
            Log.d(TAG, PREFIX + message)
        }
    }

    @JvmStatic
    fun error(message: String) {
        if(isLoggingEnabled) {
            Log.e(TAG, PREFIX + message)
        }
    }

    @JvmStatic
    fun error(message: String, throwable: Throwable?) {
        if(isLoggingEnabled) {
            Log.e(TAG, PREFIX + message, throwable)
        }
    }
}