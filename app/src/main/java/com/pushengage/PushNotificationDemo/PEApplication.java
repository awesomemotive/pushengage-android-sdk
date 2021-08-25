package com.pushengage.PushNotificationDemo;

import android.app.Application;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PEPrefs;

public class PEApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

//        PEPrefs prefs = new PEPrefs(this);
//        prefs.setEnvironment(PEConstants.STG);

        PushEngage pushEngage = new PushEngage.Builder()
                .addContext(getApplicationContext())
                .setAppId("8cde262c-16c3-4e14-9a73-43eaec292d3c")
                .build();
    }
}
