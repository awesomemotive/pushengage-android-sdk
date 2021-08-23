package com.pushengage.pushengage.Service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
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
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.response.GenricResponse;

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
        Log.d(TAG, "NotificationService Called");

        String tag = intent.getStringExtra("tag");
        prefs = new PEPrefs(this);
        String url = intent.getStringExtra("url");
        String deepLink = intent.getStringExtra("deepLink");
        HashMap<String, String> data = (HashMap<String, String>) intent.getSerializableExtra("data");
        String action = intent.getStringExtra("action");
        int id = intent.getIntExtra("id", -1);

         if (!TextUtils.isEmpty(url)) {
            //deepLink or url available is used for navigation
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setData(Uri.parse(url));
            startActivity(i);
        } else {
             //deepLink or url not available, app is launched with additional data. User can handle navigation in their launch screen.
             try {
                String packageName = getPackageName();
                Intent i = getApplicationContext().getPackageManager().getLaunchIntentForPackage(packageName);
                i.putExtra("data", data);
                getApplicationContext().startActivity(i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(id);

        notificationCLick(this, prefs.getHash(), action, tag, false);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate Called");
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

            Call<GenricResponse> notificationClickResponseCall = RestClient.getAnalyticsClient(context, headerMap).notificationClick(deviceHash, tag, action, PEConstants.ANDROID, device, PushEngage.getSdkVersion(), PEUtilities.getTimeZone());
            notificationClickResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        GenricResponse genricResponse = response.body();
                        Log.d(TAG, "API Success");
                        stopSelf();
                    } else {
                        if (!isRetry) {
                            new Timer().schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    notificationCLick(context, deviceHash, action, tag, true);
                                }
                            }, PEConstants.RETRY_DELAY);

                        } else {
                            ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                            String jsonStr = gson.toJson(response.body());
                            ErrorLogRequest.Data data = errorLogRequest.new Data(tag, prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                            errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                            errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
                            errorLogRequest.setData(data);
                            PEUtilities.addLogs(context, TAG, errorLogRequest);
                            Log.e(TAG, "API Failure");
                            stopSelf();
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    if (!isRetry) {
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                notificationCLick(context, deviceHash, action, tag, true);
                            }
                        }, PEConstants.RETRY_DELAY);

                    } else {
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        ErrorLogRequest.Data data = errorLogRequest.new Data(tag, prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                        errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
                        errorLogRequest.setData(data);
                        PEUtilities.addLogs(context, TAG, errorLogRequest);
                        Log.e(TAG, "API Failure");
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
            ClickRequestEntity clickRequestEntity = new ClickRequestEntity(deviceHash, tag, action, PEConstants.ANDROID, device, PushEngage.getSdkVersion(), PEUtilities.getTimeZone());
            Runnable runnable = new Runnable() {
                public void run() {
                    daoInterface.insertClickRequest(clickRequestEntity);
                }
            };
            Thread thread = new Thread(runnable);
            thread.start();
            stopSelf();
        }
    }
}
