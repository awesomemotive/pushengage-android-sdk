package com.pushengage.pushengage.DataWorker;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.UpdateSubscriberRequest;
import com.pushengage.pushengage.model.response.AndroidSyncResponse;
import com.pushengage.pushengage.model.response.NetworkResponse;

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
        try {
            callAndroidSync();
        } catch (Throwable e) {
            return Result.failure();
        }
        return Result.success();

    }

    @Override
    public void onStopped() {
        super.onStopped();
    }

    /**
     * API Call to sync the device with Data from server
     */
    public void callAndroidSync() {
        if (PEUtilities.checkNetworkConnection(getApplicationContext())) {
            prefs = new PEPrefs(getApplicationContext());
            String siteKey = prefs.getSiteKey();
            if (siteKey == null || siteKey.trim().isEmpty()) {
                // WorkManager persists this worker across upgrades with KEEP policy, so a
                // misconfigured install keeps firing weekly. Short-circuit to stop the flood
                // and surface the cause in logcat instead of swallowing the 400 silently.
                PELogger.error(
                        "App ID is not configured. Call PushEngage.Builder().setAppId(\"YOUR_APP_ID\") during app startup. Weekly sync skipped.",
                        null);
                return;
            }
            Call<AndroidSyncResponse> addRecordsResponseCall = RestClient.getBackendCdnClient(getApplicationContext())
                    .androidSync(siteKey);
            addRecordsResponseCall.enqueue(new Callback<AndroidSyncResponse>() {
                @Override
                public void onResponse(@NonNull Call<AndroidSyncResponse> call,
                        @NonNull Response<AndroidSyncResponse> response) {
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
                            prefs.setFirebaseProjectId(androidSyncResponse.getData().getFirebaseProjectId());
                            prefs.setDeleteOnNotificationDisable(
                                    androidSyncResponse.getData().getDeleteOnNotificationDisable());
                            prefs.setSiteStatus(androidSyncResponse.getData().getSiteStatus());
                            prefs.setGeoFetch(androidSyncResponse.getData().getGeoLocationEnabled());
                            prefs.setEu(androidSyncResponse.getData().getIsEu());

                            // Catches dashboard drift between subscribes — a sender_id or
                            // project_id rotated incorrectly on the dashboard after initial
                            // subscribe would otherwise go unnoticed until the next subscribe()
                            // call. Listener fires within at most a week of the drift; the
                            // log line surfaces to logcat for QA. We do NOT short-circuit
                            // callUpdateSubscriberHash() below because the subscriber row
                            // already exists — only metadata refresh, no row creation.
                            PushEngage.runConfigValidation(
                                    androidSyncResponse.getData().getFirebaseSenderId(),
                                    androidSyncResponse.getData().getFirebaseProjectId());

                            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat
                                    .from(getApplicationContext());
                            boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();
                            long longVal = areNotificationsEnabled ? 0 : 1;
                            prefs.setIsNotificationDisabled(longVal);
                            if (!prefs.isManuallyUnsubscribed()) {
                                callUpdateSubscriberHash();
                            }
                        } else {
                            prefs.setIsSubscriberDeleted(true);
                        }
                    } else {
                        // Log.d(TAG, "API Failure");
                    }
                }

                @Override
                public void onFailure(@NonNull Call<AndroidSyncResponse> call, @NonNull Throwable t) {
                    // Log.d(TAG, "API Failure");
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
        Call<NetworkResponse> updateSubscriberDetailsResponseCall = RestClient.getBackendClient(getApplicationContext()).updateSubscriberHash(prefs.getHash(), updateSubscriberRequest, PushEngage.getSdkVersion(), String.valueOf(prefs.getEu()), String.valueOf(prefs.isGeoFetch()));
        updateSubscriberDetailsResponseCall.enqueue(new Callback<NetworkResponse>() {
            @Override
            public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                if (response.isSuccessful()) {
                    NetworkResponse networkResponse = response.body();
                } else {
//                    Log.d(TAG, "API Failure");
                }
            }

            @Override
            public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
//                Log.d(TAG, "API Failure");
            }
        });
    }

}