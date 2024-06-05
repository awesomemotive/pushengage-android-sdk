package com.pushengage.pushengage.Receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.gson.Gson;
import com.pushengage.pushengage.Database.ClickRequestEntity;
import com.pushengage.pushengage.Database.DaoInterface;
import com.pushengage.pushengage.Database.PERoomDatabase;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.response.NetworkResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class NetworkChangeReceiver extends BroadcastReceiver implements LifecycleOwner {
    public final String TAG = NetworkChangeReceiver.class.getName();
    private PERoomDatabase peRoomDatabase;
    private DaoInterface daoInterface;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (isOnline(context)) {
                PELogger.debug("NetworkChangeReceiver: Device online");
                PEPrefs prefs = new PEPrefs(context);
                if (TextUtils.isEmpty(prefs.getHash())) {
                    PushEngage.subscribe();
                }
                getDataFromDB(context);
            } else {
                PELogger.debug("NetworkChangeReceiver: Device offline");
            }
        } catch (NullPointerException e) {
            PELogger.error("NetworkChangeReceiver", e);
        }
    }


    private void getDataFromDB(Context context) {
        peRoomDatabase = PERoomDatabase.getDatabase(context);
        daoInterface = peRoomDatabase.daoInterface();

        Runnable runnable = new Runnable() {
            public void run() {
                List<ClickRequestEntity> clickRequestEntities = daoInterface.getAllClick();
                for (int i = 0; i < clickRequestEntities.size(); i++) {
                    notificationCLick(context, clickRequestEntities.get(i));
                }
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();

    }

    private boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            //should check null because in airplane mode it will be null
            return (netInfo != null && netInfo.isConnected());
        } catch (NullPointerException e) {
            return false;
        }
    }

    public void notificationCLick(Context context, ClickRequestEntity clickRequestEntity) {
        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("referer", "https://pushengage.com/service-worker.js");
        Call<NetworkResponse> notificationClickResponseCall = RestClient.getAnalyticsClient(context, headerMap).notificationClick(clickRequestEntity.getDeviceHash(), clickRequestEntity.getTag(), clickRequestEntity.getAction(), clickRequestEntity.getDeviceType(), clickRequestEntity.getDevice(), clickRequestEntity.getSwv(), clickRequestEntity.getTimezone());
        notificationClickResponseCall.enqueue(new Callback<NetworkResponse>() {
            @Override
            public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                if (response.isSuccessful()) {
                    NetworkResponse networkResponse = response.body();
                    daoInterface.deleteClick(clickRequestEntity.getDeviceHash(), clickRequestEntity.getTag());
//                    Log.d(TAG, "API Success");
                } else {
                    ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                    Gson gson = new Gson();
                    String jsonStr = gson.toJson(response.body());
                    ErrorLogRequest.Data data = errorLogRequest.new Data(clickRequestEntity.getTag(), clickRequestEntity.getDeviceHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                    errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                    errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
                    errorLogRequest.setData(data);
                    PEUtilities.addLogs(context, TAG, errorLogRequest);
//                    Log.d(TAG, "API Failure");
                }
            }

            @Override
            public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                ErrorLogRequest.Data data = errorLogRequest.new Data(clickRequestEntity.getTag(), clickRequestEntity.getDeviceHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
                errorLogRequest.setData(data);
                PEUtilities.addLogs(context, TAG, errorLogRequest);
//                Log.d(TAG, "API Failure");
            }
        });
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return null;
    }
}