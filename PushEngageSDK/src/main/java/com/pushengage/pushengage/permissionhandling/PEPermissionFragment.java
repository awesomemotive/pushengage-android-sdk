package com.pushengage.pushengage.permissionhandling;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.helper.PEConstants;

/**
 * Permission handler that works with both ComponentActivity (Compose) and
 * FragmentActivity (traditional).
 * For Compose apps, provides guidance on manual permission handling.
 */
public class PEPermissionFragment extends Fragment {

    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final String FRAGMENT_TAG = "PEPermissionFragment";

    private PushEngagePermissionCallback permissionCallback;

    /**
     * Request notification permission - works with both ComponentActivity (Compose)
     * and FragmentActivity (traditional)
     * 
     * @param activity The activity to request permission from
     * @param callback The callback to be invoked when permission result is received
     */
    public static void requestPermission(ComponentActivity activity, PushEngagePermissionCallback callback) {
        if (activity == null) {
            PELogger.error("Activity cannot be null for permission request", null);
            if (callback != null) {
                callback.onPermissionResult(false, new Error("Activity cannot be null"));
            }
            return;
        }

        if (callback == null) {
            PELogger.error("Callback cannot be null for permission request", null);
            return;
        }

        try {
            // Check if ComponentActivity extends FragmentActivity
            if (activity instanceof FragmentActivity) {
                PELogger.debug("ComponentActivity extends FragmentActivity - using fragment approach");
                FragmentActivity fragmentActivity = (FragmentActivity) activity;
                requestPermissionWithFragment(fragmentActivity, callback);
            } else {
                PELogger.debug("Pure ComponentActivity - using invisible helper activity");
                PEPermissionHelperActivity.requestPermission(activity, callback);
            }
        } catch (Exception e) {
            PELogger.error("Error requesting notification permission", e);
            callback.onPermissionResult(false, new Error("Failed to request permission: " + e.getMessage()));
        }
    }

    /**
     * Request permission using fragment approach (for FragmentActivity)
     */
    private static void requestPermissionWithFragment(FragmentActivity activity,
            PushEngagePermissionCallback callback) {
        try {
            FragmentManager fragmentManager = activity.getSupportFragmentManager();

            // Remove any existing fragment with the same tag
            Fragment existingFragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG);
            if (existingFragment != null) {
                fragmentManager.beginTransaction().remove(existingFragment).commitNow();
            }

            // Create and add new permission fragment
            PEPermissionFragment fragment = new PEPermissionFragment();
            fragment.permissionCallback = callback;

            fragmentManager.beginTransaction()
                    .add(fragment, FRAGMENT_TAG)
                    .commitNow();

        } catch (Exception e) {
            PELogger.error("Error requesting notification permission with fragment", e);
            callback.onPermissionResult(false, new Error("Failed to request permission: " + e.getMessage()));
        }
    }



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true); // Retain during configuration changes

        // For Android 13+ (API 33+), request POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= 33) {
            PELogger.debug("Requesting POST_NOTIFICATIONS permission - system dialog should appear");
            requestNotificationPermission();
        } else {
            // For versions below Android 13, notification permission is granted by default
            PELogger.debug("Permission not required for Android version below 13");
            handlePermissionResultAndCleanup(true, null);
        }
    }

    @RequiresApi(33)
    private void requestNotificationPermission() {
        PELogger.debug("=== REQUESTING SYSTEM PERMISSION ===");
        if (getContext() == null) {
            PELogger.error("Context is null - cannot request permission", null);
            handlePermissionResultAndCleanup(false, new Error("Context is null"));
            return;
        }

        int permissionState = ContextCompat.checkSelfPermission(getContext(), POST_NOTIFICATIONS);

        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted
            PELogger.debug("Notification permission already granted - skipping dialog");
            handlePermissionResultAndCleanup(true, null);
        } else {
            // Request permission using fragment approach
            requestPermissions(new String[] { POST_NOTIFICATIONS }, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            PELogger.debug("Final result: " + (granted ? "GRANTED" : "DENIED"));
            handlePermissionResultAndCleanup(granted, granted ? null : new Error("Permission denied"));
        } else {
            PELogger.debug("Unknown request code - ignoring");
        }
    }

    /**
     * Handle the permission result and invoke the callback
     * 
     * @param granted  whether the permission was granted
     * @param error    any error that occurred
     * @param callback the callback to invoke
     */
    private static void handlePermissionResult(boolean granted, Error error, PushEngagePermissionCallback callback) {
        try {
            // If permission is granted, automatically call subscribe
            if (granted) {
                PELogger.debug("Permission granted, automatically calling subscribe");
                PushEngage.subscribe();
            }

            // Then invoke the user's callback
            if (callback != null) {
                callback.onPermissionResult(granted, error);
            }
        } catch (Exception e) {
            PELogger.error("Error invoking permission callback", e);
        }
    }

    /**
     * Handle permission result for fragment-based requests and clean up
     */
    private void handlePermissionResultAndCleanup(boolean granted, Error error) {
        handlePermissionResult(granted, error, permissionCallback);
        // Remove the fragment after handling the result
        removeSelf();
    }

    /**
     * Remove this fragment from the fragment manager
     */
    private void removeSelf() {
        try {
            if (getFragmentManager() != null) {
                getFragmentManager().beginTransaction().remove(this).commitNow();
            }
        } catch (Exception e) {
            PELogger.error("Error removing permission fragment", e);
        }

        // Clear callback to prevent memory leaks
        permissionCallback = null;
    }
}