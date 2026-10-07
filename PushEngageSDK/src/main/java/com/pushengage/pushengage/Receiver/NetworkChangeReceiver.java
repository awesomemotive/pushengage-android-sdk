package com.pushengage.pushengage.Receiver;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Process;
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
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Response;

public class NetworkChangeReceiver extends BroadcastReceiver implements LifecycleOwner {
    public final String TAG = NetworkChangeReceiver.class.getName();
    private PERoomDatabase peRoomDatabase;
    private DaoInterface daoInterface;
    /** One replay pass at a time: connectivity broadcasts arrive in bursts on Android 14. */
    private static final AtomicBoolean REPLAY_IN_PROGRESS = new AtomicBoolean(false);

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!isMainProcess(context)) {
                PELogger.debug("NetworkChangeReceiver: not the main process, ignoring connectivity change");
                return;
            }
            if (isOnline(context)) {
                PELogger.debug("NetworkChangeReceiver: Device online");
                PEPrefs prefs = new PEPrefs(context);
                if (TextUtils.isEmpty(prefs.getHash()) &&
                        "granted".equals(PushEngage.getNotificationPermissionStatus()) &&
                        !prefs.isManuallyUnsubscribed()) {
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
        // Android 14 delivers deferred connectivity broadcasts to a cached app in a burst, so
        // only one replay pass may run at a time. A pass deletes each row the moment the server
        // accepts it, so any later pass finds the table already drained.
        if (!REPLAY_IN_PROGRESS.compareAndSet(false, true)) {
            PELogger.debug("NetworkChangeReceiver: click replay already in progress");
            return;
        }
        peRoomDatabase = PERoomDatabase.getDatabase(context);
        daoInterface = peRoomDatabase.daoInterface();

        Runnable runnable = new Runnable() {
            public void run() {
                try {
                    List<ClickRequestEntity> clickRequestEntities = daoInterface.getAllClick();
                    for (ClickRequestEntity clickRequestEntity : clickRequestEntities) {
                        if (!notificationCLick(context, clickRequestEntity)) {
                            break; // network failed: leave the remaining rows queued for the next pass
                        }
                    }
                } catch (Exception e) {
                    // This runs in the host app's main process on every connectivity change,
                    // including the sticky broadcast delivered at SDK initialisation, so it is
                    // the first Room open of the process at every launch. A database failure
                    // here must be reported, never allowed to become a launch crash.
                    PELogger.error("Replay queued notification clicks", e);
                    reportReplayFailure(context, e);
                } finally {
                    REPLAY_IN_PROGRESS.set(false);
                }
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
    }

    /**
     * Sends a replay failure to the SDK's error log. Best effort and guarded, so a reporting
     * problem can never escape the catch block that called it.
     */
    private void reportReplayFailure(Context context, Exception e) {
        try {
            ErrorLogRequest errorLogRequest = new ErrorLogRequest();
            ErrorLogRequest.Data data = errorLogRequest.new Data("", new PEPrefs(context).getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), "replay failed: " + e.getMessage());
            errorLogRequest.setApp(PEConstants.ANDROID_SDK);
            errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
            errorLogRequest.setData(data);
            PEUtilities.addLogs(context, TAG, errorLogRequest);
        } catch (Exception reportFailure) {
            PELogger.error("Report click replay failure", reportFailure);
        }
    }

    /**
     * Whether this receiver instance runs in the app's main process. Host apps commonly call
     * {@code PushEngage.Builder().build()} from {@code Application.onCreate()}, which also runs
     * in the SDK's {@code :RegisterReceiverService} process, so without this check every
     * connectivity change is handled twice and every queued click is replayed twice.
     * Overridable for tests. Fails open to "main" when the process name cannot be determined.
     */
    protected boolean isMainProcess(Context context) {
        String processName = currentProcessName(context);
        if (processName == null) {
            return true;
        }
        // The default process is named after the package unless the app sets android:process on
        // <application>; ApplicationInfo.processName holds that declared name.
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        String mainProcessName = applicationInfo != null && applicationInfo.processName != null
                ? applicationInfo.processName
                : context.getPackageName();
        return processName.equals(mainProcessName);
    }

    private static String currentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return null;
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) return null;
        int pid = Process.myPid();
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (info.pid == pid) return info.processName;
        }
        return null;
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

    /**
     * Sends one queued click synchronously and deletes its row once the server has accepted it.
     * Runs on the replay thread. Returns false when the network itself failed, so the caller
     * stops the pass and leaves the remaining rows queued; a server rejection returns true so
     * the pass moves on to the next row.
     */
    public boolean notificationCLick(Context context, ClickRequestEntity clickRequestEntity) {
        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("referer", "https://pushengage.com/service-worker.js");
        try {
            Response<NetworkResponse> response = RestClient.getAnalyticsClient(context, headerMap)
                    .notificationClick(clickRequestEntity.getDeviceHash(), clickRequestEntity.getTag(), clickRequestEntity.getAction(), clickRequestEntity.getDeviceType(), clickRequestEntity.getDevice(), clickRequestEntity.getSwv(), clickRequestEntity.getTimezone())
                    .execute();
            if (response.isSuccessful()) {
                deleteQueuedClick(clickRequestEntity);
            } else {
                reportClickReplayFailure(context, clickRequestEntity, new Gson().toJson(response.body()));
            }
            return true;
        } catch (Exception e) {
            reportClickReplayFailure(context, clickRequestEntity, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes by row id. Two taps on the same notification share hash and tag, and deleting by
     * those would also drop a sibling row that has not been sent yet.
     */
    private void deleteQueuedClick(ClickRequestEntity clickRequestEntity) {
        if (clickRequestEntity.getId() != null) {
            daoInterface.deleteClickById(clickRequestEntity.getId());
        } else {
            daoInterface.deleteClick(clickRequestEntity.getDeviceHash(), clickRequestEntity.getTag());
        }
    }

    private void reportClickReplayFailure(Context context, ClickRequestEntity clickRequestEntity, String error) {
        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
        ErrorLogRequest.Data data = errorLogRequest.new Data(clickRequestEntity.getTag(), clickRequestEntity.getDeviceHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), error);
        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
        errorLogRequest.setName(PEConstants.CLICK_COUNT_TRACKING_FAILED);
        errorLogRequest.setData(data);
        PEUtilities.addLogs(context, TAG, errorLogRequest);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return null;
    }
}