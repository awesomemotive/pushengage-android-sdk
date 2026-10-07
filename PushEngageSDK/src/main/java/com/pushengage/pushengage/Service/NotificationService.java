package com.pushengage.pushengage.Service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class NotificationService extends Service {

    public static final String TAG = "NotificationService";
    private static PEPrefs prefs;
    private PERoomDatabase peRoomDatabase;
    private DaoInterface daoInterface;
    private Gson gson = new Gson();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String tag = intent.getStringExtra(PEConstants.TAG_EXTRA);
        prefs = new PEPrefs(this);
        String action = intent.getStringExtra(PEConstants.ACTION_EXTRA);
        int id = intent.getIntExtra(PEConstants.ID_EXTRA, -1);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(id);

        notificationCLick(this, prefs.getHash(), action, tag, false);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * API call tacking Notification Clicks(analytics).
     * @param context
     * @param deviceHash
     * @param action
     * @param tag
     * @param isRetry
     */
    public void notificationCLick(Context context, String deviceHash, String action, String tag, boolean isRetry) {
        if (prefs == null && context != null) {
            prefs = new PEPrefs(context);
        }
        // A click cannot be attributed without its notification tag and the analytics API
        // rejects the request, so there is nothing useful to send or queue. Report it through
        // the SDK's error log so dropped clicks stay measurable (PELogger is off by default).
        if (tag == null || tag.isEmpty()) {
            PELogger.debug("Notification click not tracked: missing tag");
            reportClickTrackingFailure(context, "", "missing tag");
            stopSelf();
            return;
        }
        // Body taps carry no action; only action buttons set one. Track them as "" so the
        // value satisfies the NOT NULL ClickRequest.action column when queued offline. The
        // analytics API counts an empty action as a plain click, same as an absent one.
        final String safeAction = action == null ? "" : action;
        final String safeDeviceHash = deviceHash == null ? "" : deviceHash;
        if (PEUtilities.checkNetworkConnection(context)) {
            Map<String, String> headerMap = new HashMap<>();
            headerMap.put("referer", "https://pushengage.com/service-worker.js");
            String device = "";
            TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (Objects.requireNonNull(manager).getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
                device = PEConstants.TABLET;
            } else {
                device = PEConstants.MOBILE;
            }

            Call<NetworkResponse> notificationClickResponseCall = RestClient.getAnalyticsClient(context, headerMap).notificationClick(safeDeviceHash, tag, safeAction, PEConstants.ANDROID, device, PushEngage.getSdkVersion(), PEUtilities.getTimeZone());
            notificationClickResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse networkResponse = response.body();
                        stopSelf();
                    } else {
                        if (!isRetry) {
                            new Timer().schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    notificationCLick(context, safeDeviceHash, safeAction, tag, true);
                                }
                            }, PEConstants.RETRY_DELAY);

                        } else {
                            ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                            String jsonStr = gson.toJson(response.body());
                            ErrorLogRequest.Data data = errorLogRequest.new Data(tag, getPrefsHashSafe(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                            errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                            errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
                            errorLogRequest.setData(data);
                            PEUtilities.addLogs(context, TAG, errorLogRequest);
                            stopSelf();
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (!isRetry) {
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                notificationCLick(context, safeDeviceHash, safeAction, tag, true);
                            }
                        }, PEConstants.RETRY_DELAY);

                    } else {
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        ErrorLogRequest.Data data = errorLogRequest.new Data(tag, getPrefsHashSafe(), PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                        errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
                        errorLogRequest.setData(data);
                        PEUtilities.addLogs(context, TAG, errorLogRequest);
                        stopSelf();
                    }
                }
            });
        } else {
            peRoomDatabase = PERoomDatabase.getDatabase(context);
            daoInterface = peRoomDatabase.daoInterface();
            String device = "";
            TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (Objects.requireNonNull(manager).getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
                device = PEConstants.TABLET;
            } else {
                device = PEConstants.MOBILE;
            }
            ClickRequestEntity clickRequestEntity = new ClickRequestEntity(safeDeviceHash, tag, safeAction, PEConstants.ANDROID, device, PushEngage.getSdkVersion(), PEUtilities.getTimeZone());
            Runnable runnable = new Runnable() {
                public void run() {
                    try {
                        daoInterface.insertClickRequest(clickRequestEntity);
                    } catch (Exception e) {
                        // Click analytics must never take the host app's process down.
                        PELogger.error("Queue notification click", e);
                        reportClickTrackingFailure(context, tag, "queue failed: " + e.getMessage());
                    } finally {
                        // Stop only once the row is written. This service is the sole component
                        // in its process, so stopping earlier leaves an empty process that can be
                        // reclaimed mid-write, losing the click.
                        stopSelf();
                    }
                }
            };
            Thread thread = new Thread(runnable);
            thread.start();
        }
    }

    /**
     * Sends a click-tracking failure to the SDK's error log. Best effort: when the device is
     * offline the request simply fails quietly, like the other error logs in this service.
     */
    private void reportClickTrackingFailure(Context context, String tag, String reason) {
        try {
            ErrorLogRequest errorLogRequest = new ErrorLogRequest();
            ErrorLogRequest.Data data = errorLogRequest.new Data(tag, getPrefsHashSafe(), PEConstants.MOBILE, PEUtilities.getTimeZone(), reason);
            errorLogRequest.setApp(PEConstants.ANDROID_SDK);
            errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
            errorLogRequest.setData(data);
            PEUtilities.addLogs(context, TAG, errorLogRequest);
        } catch (Exception e) {
            PELogger.error("Report click tracking failure", e);
        }
    }

    private String getPrefsHashSafe() {
        if (prefs == null) {
            return "";
        }
        String hash = prefs.getHash();
        return hash == null ? "" : hash;
    }
}
