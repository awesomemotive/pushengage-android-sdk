package com.pushengage.pushengage.DataWorker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest;
import com.pushengage.pushengage.model.response.GenricResponse;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DailySyncDataWorker extends Worker {
    private static final String TAG = DailySyncDataWorker.class.getSimpleName();
    private static PEPrefs prefs;

    public DailySyncDataWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Syncing Data with Server");
        prefs = new PEPrefs(getApplicationContext());
        try {
            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
            boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();

            long longVal = areNotificationsEnabled ? 0 : 1;
            if (!(longVal == prefs.isNotificationDisabled())) {
                if (areNotificationsEnabled && prefs.isSubscriberDeleted()) {
                    PushEngage.callAddSubscriberAPI();
                } else {
                    UpdateSubscriberStatusRequest updateSubscriberStatusRequest = new UpdateSubscriberStatusRequest(prefs.getSiteId(), prefs.getHash(), longVal, prefs.getDeleteOnNotificationDisable());
                    updateSubscriberStatus(updateSubscriberStatusRequest);
                }
            }
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
     * API call to Updatev the subscriber status based on Devices Notification permission.
     *
     * @param updateSubscriberStatusRequest
     */
    public void updateSubscriberStatus(UpdateSubscriberStatusRequest updateSubscriberStatusRequest) {
        updateSubscriberStatusRequest.setDeviceTokenHash(prefs.getHash());
        Call<GenricResponse> updateSubscriberStatusResponseCall = RestClient.getBackendClient(getApplicationContext()).updateSubscriberStatus(updateSubscriberStatusRequest);
        updateSubscriberStatusResponseCall.enqueue(new Callback<GenricResponse>() {
            @Override
            public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                if (response.isSuccessful()) {
                    GenricResponse genricResponse = response.body();
                    prefs.setIsNotificationDisabled(updateSubscriberStatusRequest.getIsUnSubscribed());
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