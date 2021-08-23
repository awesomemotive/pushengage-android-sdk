package com.pushengage.pushengage.DataWorker;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.UpdateSubscriberRequest;
import com.pushengage.pushengage.model.response.AndroidSyncResponse;
import com.pushengage.pushengage.model.response.GenricResponse;

import java.util.Locale;
import java.util.Objects;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class WeeklySyncDataWorker extends Worker {
    private static final String TAG = WeeklySyncDataWorker.class.getSimpleName();
    private static PEPrefs prefs;

    public WeeklySyncDataWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Syncing Data with Server");
        try {
            callAndroidSync();
        } catch (Throwable e) {
            e.printStackTrace();
            // Technically WorkManager will return Result.failure()
            // but it's best to be explicit about it.
            // Thus if there were errors, we're return FAILURE
            Log.e(TAG, "Error fetching data", e);
            return Result.failure();
        }
        return Result.success();

    }

    @Override
    public void onStopped() {
        super.onStopped();
        Log.i(TAG, "OnStopped called for this worker");
    }

    /**
     * API Call to sync the device with Data from server
     */
    public void callAndroidSync() {
        if (PEUtilities.checkNetworkConnection(getApplicationContext())) {
            prefs = new PEPrefs(getApplicationContext());
            Log.e(TAG, " SiteKey = " + prefs.getSiteKey());
            Call<AndroidSyncResponse> addRecordsResponseCall = RestClient.getBackendCdnClient(getApplicationContext()).androidSync(prefs.getSiteKey());
            addRecordsResponseCall.enqueue(new Callback<AndroidSyncResponse>() {
                @Override
                public void onResponse(@NonNull Call<AndroidSyncResponse> call, @NonNull Response<AndroidSyncResponse> response) {
                    if (response.isSuccessful()) {
                        AndroidSyncResponse androidSyncResponse = response.body();
                        if (androidSyncResponse.getData().getSiteStatus().equalsIgnoreCase(PEConstants.ACTIVE)) {
                            prefs.setBackendUrl(androidSyncResponse.getData().getApi().getBackend());
                            prefs.setBackendCdnUrl(androidSyncResponse.getData().getApi().getBackendCdn());
                            prefs.setAnalyticsUrl(androidSyncResponse.getData().getApi().getAnalytics());
                            prefs.setTriggerUrl(androidSyncResponse.getData().getApi().getTrigger());
                            prefs.setOptinUrl(androidSyncResponse.getData().getApi().getOptin());
                            prefs.setLoggerUrl(androidSyncResponse.getData().getApi().getLog());
                            prefs.setSiteId(androidSyncResponse.getData().getSiteId());
                            prefs.setProjectId(androidSyncResponse.getData().getFirebaseSenderId());
                            prefs.setDeleteOnNotificationDisable(androidSyncResponse.getData().getDeleteOnNotificationDisable());
                            prefs.setSiteStatus(androidSyncResponse.getData().getSiteStatus());
                            prefs.setGeoFetch(androidSyncResponse.getData().getGeoLocationEnabled());
                            prefs.setEu(androidSyncResponse.getData().getIsEu());
                            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
                            boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();
                            long longVal = areNotificationsEnabled ? 0 : 1;
                            prefs.setIsNotificationDisabled(longVal);
                            callUpdateSubscriberHash();
                        } else {
                            prefs.setIsSubscriberDeleted(true);
                            Log.e(TAG, "Site Status = " + androidSyncResponse.getData().getSiteStatus());
                        }
                    } else {
                        Log.e(TAG, "API Failure");
                    }
                }

                @Override
                public void onFailure(@NonNull Call<AndroidSyncResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                }
            });
        }
    }

    /**
     * API call to update subscriber hash
     */
    private void callUpdateSubscriberHash() {
        prefs = new PEPrefs(getApplicationContext());
        String device = "";
        TelephonyManager manager = (TelephonyManager) getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
        if (Objects.requireNonNull(manager).getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
            device = PEConstants.TABLET;
        } else {
            device = PEConstants.MOBILE;
        }
        String deviceVersion = Build.VERSION.RELEASE;
        String timeZone = PEUtilities.getTimeZone();
        String language = Locale.getDefault().getLanguage();
        int width = Resources.getSystem().getDisplayMetrics().widthPixels;
        int height = Resources.getSystem().getDisplayMetrics().heightPixels;
        String screenSize = width + "*" + height;
        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();
        long longVal = areNotificationsEnabled ? 0 : 1;
        prefs.setIsNotificationDisabled(longVal);
        UpdateSubscriberRequest updateSubscriberRequest = new UpdateSubscriberRequest(prefs.getSiteId(), device, deviceVersion, timeZone, language, screenSize, longVal);
        Call<GenricResponse> updateSubscriberDetailsResponseCall = RestClient.getBackendClient(getApplicationContext()).updateSubscriberHash(prefs.getHash(), updateSubscriberRequest, PushEngage.getSdkVersion(), String.valueOf(prefs.getEu()), String.valueOf(prefs.isGeoFetch()));
        updateSubscriberDetailsResponseCall.enqueue(new Callback<GenricResponse>() {
            @Override
            public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                if (response.isSuccessful()) {
                    GenricResponse genricResponse = response.body();
                } else {
                    Log.e(TAG, "API Failure");
                }
            }

            @Override
            public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "API Failure");
            }
        });
    }

}