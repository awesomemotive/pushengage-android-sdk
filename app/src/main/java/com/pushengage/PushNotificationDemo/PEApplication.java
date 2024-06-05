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
        prefs.setEnvironment(PEConstants.STG);

        PushEngage pushEngage = new PushEngage.Builder()
                .addContext(getApplicationContext())
                .setAppId("3ca8257d-1f40-41e0-88bc-ea28dc6495ef")
                .build();

        PushEngage.enableLogging(true);
    }
}
