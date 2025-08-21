package com.pushengage.pushengage.permissionhandling;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.helper.PELogger;

/**
 * Completely invisible helper activity for automatic permission handling
 */
public class PEPermissionHelperActivity extends ComponentActivity {

    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static PushEngagePermissionCallback pendingCallback;

    public static void requestPermission(ComponentActivity originalActivity, PushEngagePermissionCallback callback) {
        PELogger.debug("Launching invisible permission helper");
        pendingCallback = callback;

        Intent intent = new Intent(originalActivity, PEPermissionHelperActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        originalActivity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make window completely invisible - 1x1 pixel positioned off-screen
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = 1;
        params.height = 1;
        params.x = -1000;
        params.y = -1000;
        params.alpha = 0.0f;
        getWindow().setAttributes(params);

        // Remove from task switcher immediately (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(new android.app.ActivityManager.TaskDescription("", null, 0));
        }

        PELogger.debug("Invisible helper created");

        // Request permission or finish immediately
        if (Build.VERSION.SDK_INT >= 33) {
            // Check if permission is already granted
            int permissionState = ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS);

            if (permissionState == PackageManager.PERMISSION_GRANTED) {
                PELogger.debug("Permission already granted");
                handleResult(true);
                return;
            }

            PELogger.debug("Requesting POST_NOTIFICATIONS permission");
            ActivityCompat.requestPermissions(this, new String[] { POST_NOTIFICATIONS }, PERMISSION_REQUEST_CODE);
        } else {
            PELogger.debug("Permission not required for Android < 13");
            handleResult(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            PELogger.debug("Permission result: " + granted);
            handleResult(granted);
        }
    }

    private void handleResult(boolean granted) {
        try {
            // If permission is granted, automatically call subscribe
            if (granted) {
                PELogger.debug("Permission granted, automatically calling subscribe");
                PushEngage.subscribe();
            }

            // Then invoke the user's callback
            if (pendingCallback != null) {
                pendingCallback.onPermissionResult(granted, granted ? null : new Error("Permission denied"));
                pendingCallback = null;
            }
        } catch (Exception e) {
            PELogger.error("Error handling permission result", e);
        }

        // Finish immediately without animation
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        // Ensure no animation on finish
        overridePendingTransition(0, 0);
    }
}