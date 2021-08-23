package com.pushengage.PushNotificationDemo;

import android.app.Application;

import com.pushengage.pushengage.PushEngage;

public class PEApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        PushEngage pushEngage = new PushEngage.Builder()
                .addContext(getApplicationContext())
                .setAppId("2d1b475e-cc73-42a1-8f13-58c944e3")
                .build();
    }
}
