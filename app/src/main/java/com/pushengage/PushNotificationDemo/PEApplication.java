package com.pushengage.PushNotificationDemo;

import android.app.Application;
import android.text.TextUtils;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.helper.PEPrefs;

/**
 * Application entry-point for the PushEngage demo app.
 *
 * Most of this class is QA scaffolding (runtime app-ID entry, staging-vs-prod
 * toggle, etc.) so the same APK can be repointed at different PushEngage sites
 * without rebuilding. Real consumer apps should NOT copy any of that — see the
 * "REAL CONSUMER INTEGRATION" block below for the only lines that matter.
 */
public class PEApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // ---------------------------------------------------------------
        // DEMO-ONLY SCAFFOLDING — delete this whole block in a real app.
        //
        // Reads the app ID from a settings screen so QA can swap sites at
        // runtime. A real consumer hardcodes their site ID below (or pulls
        // it from BuildConfig / a manifest meta-data entry).
        //
        // The setEnvironment() call reaches into the SDK's internal
        // PEPrefs because the SDK doesn't (yet) expose environment
        // switching through its public API. Do not copy this pattern.
        // ---------------------------------------------------------------
        DemoPrefs demoPrefs = new DemoPrefs(this);
        String appId = demoPrefs.getAppId();
        if (TextUtils.isEmpty(appId)) {
            // No app ID configured yet — SettingsActivity will collect it and restart.
            return;
        }
        PEPrefs sdkPrefs = new PEPrefs(this);
        sdkPrefs.setEnvironment(demoPrefs.getEnvironment());
        // ---------------------------------------------------------------
        // END DEMO-ONLY SCAFFOLDING
        // ---------------------------------------------------------------

        // ===============================================================
        // REAL CONSUMER INTEGRATION — this is what your app needs.
        //
        // Replace `appId` with your PushEngage site ID (e.g. a string
        // literal or BuildConfig.PUSHENGAGE_SITE_ID).
        // ===============================================================
        // Enable verbose SDK logging BEFORE build() so init-time logs (including
        // the IAM sync) are visible.
        PushEngage.enableLogging(true);

        new PushEngage.Builder()
                .addContext(getApplicationContext())
                .setAppId(appId)
                .build();

        // Register the IAM custom-action listener app-wide: campaigns with
        // `custom` buttons can display on ANY screen (e.g. app-open campaigns on
        // MainActivity), so registering it only inside InAppMessagingActivity
        // leaves taps unhandled everywhere else ("No custom action listener set").
        PushEngage.setIAMCustomActionHandler((actionId, parameters) -> {
            android.util.Log.d("PEApplication", "IAM custom action: " + actionId + " params: " + parameters);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(getApplicationContext(),
                            "Custom action: " + actionId, android.widget.Toast.LENGTH_LONG).show());
        });
    }
}
