package com.pushengage.pushengage.Callbacks

/**
 * Receives FCM configuration error notifications from the PushEngage SDK.
 *
 * Fires for three distinct misconfiguration classes (see `PEErrorCodes`):
 *   - `FCM_SENDER_ID_MISMATCH` (5001)
 *   - `FCM_PROJECT_ID_MISMATCH` (5002)
 *   - `FCM_LOCAL_CONFIG_INVALID` (5003)
 *   - `FCM_CONFIG_BOTH_MISMATCH` (5004) — both sender_id and project_id differ
 *
 * Threading: invoked on whichever thread the trigger ran on — OkHttp dispatcher
 * for sync-time mismatches, an arbitrary background thread for FIS BAD_CONFIG
 * results, the caller's thread for the init-time advisory check. Marshal to the
 * UI thread inside the callback before touching UI.
 *
 * Stale-data note: the init-time advisory check runs against cached preferences
 * from the previous successful sync. If a developer has just fixed a dashboard
 * misconfig but the SDK has not yet completed a fresh sync, the cached values
 * may still show the old mismatch and trigger this listener — the subsequent
 * sync will not re-fire. Debounce or wait for stable state before taking
 * destructive action.
 */
fun interface FcmConfigErrorListener {
    fun onFcmConfigError(errorCode: Int, message: String)
}
