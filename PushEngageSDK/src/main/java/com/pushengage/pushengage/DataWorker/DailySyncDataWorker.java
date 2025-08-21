package com.pushengage.pushengage.DataWorker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest;
import com.pushengage.pushengage.model.response.NetworkResponse;

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
        prefs = new PEPrefs(getApplicationContext());
        try {
            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat
                    .from(getApplicationContext());
            boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();

            long longVal = areNotificationsEnabled ? 0 : 1;
            if (!(longVal == prefs.isNotificationDisabled())) {
                if (areNotificationsEnabled && prefs.isSubscriberDeleted() && !prefs.isManuallyUnsubscribed()) {
                    PushEngage.callAddSubscriberAPI();
                } else if (!prefs.isManuallyUnsubscribed()) {
                    UpdateSubscriberStatusRequest updateSubscriberStatusRequest = new UpdateSubscriberStatusRequest(
                            prefs.getSiteId(), prefs.getHash(), longVal, prefs.getDeleteOnNotificationDisable());
                    updateSubscriberStatus(updateSubscriberStatusRequest);
                }
            }
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
     * API call to Updatev the subscriber status based on Devices Notification permission.
     *
     * @param updateSubscriberStatusRequest
     */
    public void updateSubscriberStatus(UpdateSubscriberStatusRequest updateSubscriberStatusRequest) {
        updateSubscriberStatusRequest.setDeviceTokenHash(prefs.getHash());
        Call<NetworkResponse> updateSubscriberStatusResponseCall = RestClient.getBackendClient(getApplicationContext()).updateSubscriberStatus(updateSubscriberStatusRequest);
        updateSubscriberStatusResponseCall.enqueue(new Callback<NetworkResponse>() {
            @Override
            public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                if (response.isSuccessful()) {
                    NetworkResponse networkResponse = response.body();
                    prefs.setIsNotificationDisabled(updateSubscriberStatusRequest.getIsUnSubscribed());
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