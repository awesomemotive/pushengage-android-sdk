package com.pushengage.PushNotificationDemo;

import android.content.Context;
import android.content.SharedPreferences;

import com.pushengage.pushengage.helper.PEConstants;

public class DemoPrefs {

    private static final String PREFS_NAME = "pe_demo_prefs";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_ENVIRONMENT = "environment";

    private final SharedPreferences prefs;

    public DemoPrefs(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getAppId() {
        return prefs.getString(KEY_APP_ID, "");
    }

    public void setAppId(String appId) {
        prefs.edit().putString(KEY_APP_ID, appId == null ? "" : appId.trim()).apply();
    }

    public String getEnvironment() {
        return prefs.getString(KEY_ENVIRONMENT, PEConstants.PROD);
    }

    public void setEnvironment(String environment) {
        prefs.edit().putString(KEY_ENVIRONMENT, environment).apply();
    }

    public boolean isConfigured() {
        return !getAppId().isEmpty();
    }
}
