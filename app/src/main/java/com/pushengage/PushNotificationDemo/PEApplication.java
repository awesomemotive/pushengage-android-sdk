package com.pushengage.PushNotificationDemo;

import android.app.Application;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PEPrefs;

public class PEApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        PEPrefs prefs = new PEPrefs(this);

        PushEngage pushEngage = new PushEngage.Builder()
                .addContext(getApplicationContext())
                .setAppId("YOUR_APP_ID")
                .build();

        PushEngage.enableLogging(true);
    }
}
