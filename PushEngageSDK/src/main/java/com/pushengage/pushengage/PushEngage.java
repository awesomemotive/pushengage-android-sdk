package com.pushengage.pushengage;

import static kotlin.io.TextStreamsKt.readText;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback;
import com.pushengage.pushengage.DataWorker.DailySyncDataWorker;
import com.pushengage.pushengage.DataWorker.WeeklySyncDataWorker;
import com.pushengage.pushengage.Receiver.NetworkChangeReceiver;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest;
import com.pushengage.pushengage.model.request.AddProfileIdRequest;
import com.pushengage.pushengage.model.request.AddSegmentRequest;
import com.pushengage.pushengage.model.request.AddSubscriberRequest;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.request.Goal;
import com.pushengage.pushengage.model.request.RecordsRequest;
import com.pushengage.pushengage.model.request.RemoveSegmentRequest;
import com.pushengage.pushengage.model.request.SegmentHashArrayRequest;
import com.pushengage.pushengage.model.request.TriggerAlert;
import com.pushengage.pushengage.model.request.TriggerCampaign;
import com.pushengage.pushengage.model.request.UpdateTriggerStatusRequest;
import com.pushengage.pushengage.model.response.AddSubscriberResponse;
import com.pushengage.pushengage.model.response.AndroidSyncResponse;
import com.pushengage.pushengage.model.response.NetworkResponse;
import com.pushengage.pushengage.model.response.RecordsResponse;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import kotlin.io.TextStreamsKt;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PushEngage {

    private static Context context;
    private static String TAG = "PushEngage";
    private static PEPrefs prefs;
    private static final int RETRY_COUNT = 3;
    private static int addSubscribeRetryCount = 0, siteSyncRetryCount = 0;
    private static final Gson gson = new Gson();
    private static final int DELAY = 180000;
    private static UpdateTriggerStatusRequest updateTriggerStatusRequest;
    private static Boolean isFirebaseApiCallInProgress = false;
    private static PEManagerType peManager;

    public enum TriggerAlertAvailabilityType {
        inStock,
        outOfStock
    }
    public enum TriggerStatusType {
        enabled,
        disabled
    }

    public enum TriggerAlertType {
        priceDrop,
        inventory
    }

    public interface SubscriberFields {
        String City = "city";
        String Country = "country";
        String Device = "device";
        String DeviceType = "device_type";
        String ProfileId = "profile_id";
        String Segments = "segments";
        String State = "state";
        String Timezone = "timezone";
        String TsCreated = "ts_created";
    }

    /**
     * Initializes the PushEngage library with the specified Android application context
     * and site key.
     *
     * @param context The Android application context in which the library will operate.
     * @param siteKey The unique identifier for the PushEngage site.
     */
    private PushEngage(Context context, String siteKey) {
        this.context = context;
        prefs = new PEPrefs(context);
        prefs.setSiteKey(siteKey);
        registerNetworkReceiver();
        addSubscribeRetryCount = 0;
        siteSyncRetryCount = 0;
        peManager = new PEManager(context, prefs);
        if (TextUtils.isEmpty(prefs.getHash())) {
            subscribe();
        }
    }

    public static class Builder {
        private Context context;
        private String siteKey;

        public Builder addContext(Context context) {
            this.context = context;
            return this;
        }

        public Builder setAppId(String siteKey) {
            this.siteKey = siteKey;
            return this;
        }

        public PushEngage build() {
            return new PushEngage(context, siteKey);
        }

    }

    /**
     * Registers a network receiver to monitor network connectivity changes. This enables
     * the library to provide offline support and handle network-related events.
     * When network connectivity changes, the registered {@link NetworkChangeReceiver}
     * will be notified.
     */
    private void registerNetworkReceiver() {
        BroadcastReceiver br = new NetworkChangeReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(br, filter);
    }

    /**
     * Retrieves the current version of the PushEngage SDK.
     *
     * @return A String representing the current version of the PushEngage SDK.
     * This version string is defined in the {@link PEConstants} class.
     */
    public static String getSdkVersion() {
        return PEConstants.SDK_VERSION;
    }

    /**
     * Sets the resource name of the small icon used for notifications generated by the
     * client app. The small icon appears in the status bar when a notification is displayed.
     * Note: It is recommended to set a valid resource name to ensure proper display of
     * notifications. If an invalid resource name is provided, the default bell icon
     * specified by the PushEngage library will be used.
     *
     * @param resourceName A String representing the resource name of the small icon
     *                     drawable resource in the client app.
     */
    public static void setSmallIconResource(String resourceName) {
        prefs.setSmallIconResource(resourceName);
    }

    /**
     * Retrieves the device token associated with the client app.
     *
     * @return A {@code String} representing the device token obtained from the preferences manager.
     */
    private static String getDeviceToken() {
        return prefs.getDeviceToken();
    }

    /**
     * Retrieves the device hash generated based on the device token associated with the client app.
     *
     * @return A String representing the device token associated with the client app.
     */
    public static String getDeviceTokenHash() {
        return prefs.getHash();
    }

    /**
     * Initializes Firebase Cloud Messaging for push notifications.
     * For Android 13 and above, the client should call this method once after granting
     * notification permission. This method enables auto-initialization of Firebase Cloud Messaging,
     * generates a registration token on app startup (if there is no valid one), and periodically
     * sends data to the Firebase backend to validate the token.
     */
    public static void subscribe() {
        FirebaseApp.initializeApp(context);
        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        if(isFirebaseApiCallInProgress) {
            return;
        }
        isFirebaseApiCallInProgress = true;
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull Task<String> task) {
                        isFirebaseApiCallInProgress = false;
                        if (!task.isSuccessful()) {
                            return;
                        } else {
                            // Get new FCM registration token
                            String token = task.getResult();
                            Log.d(TAG, token);
                            if (!prefs.getDeviceToken().equals(token) || prefs.getSiteId() == 0 || prefs.getHash().isEmpty()) {
                                prefs.setDeviceToken(token);
                                callAndroidSync();
                            }
                        }
                    }
                });
    }

    /**
     * Enables or disables logging for the PushEngage library based on the specified boolean value.
     *
     * @param shouldEnable A boolean value indicating whether logging should be enabled or
     *                     disabled.
     *
     * Example usage:
     * PushEngage.enableLogging(true);
     *
     */
    public static void enableLogging(Boolean shouldEnable) {
        PELogger.enableLogging(shouldEnable);
    }

    /**
     * Allows to enable or disable trigger campaigns for a subscriber
     * with the specified callback and trigger status.
     *
     * @param status   The trigger status type indicating the status of the trigger campaign.
     *                  See {@link TriggerStatusType} for possible values.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.

     *
     * Example usage:
     * PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled, object : PushEngageResponseCallback {
     *                 override fun onSuccess(responseObject: Any?) {
     *                     Toast.makeText(this@TriggerCampaignActivity, "Trigger Enabled successfully", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage: String?) {
     *                     Toast.makeText(this@TriggerCampaignActivity, "Trigger Enabled failed", Toast.LENGTH_LONG).show()
     *                 }
     *
     *             })
     */
    public static void automatedNotification(TriggerStatusType status, PushEngageResponseCallback callback) {
        peManager.automatedNotification(status, callback);
    }

    /**
     * Allows to enable or disable trigger campaigns for a subscriber
     * with a trigger status.
     *
     * @param status The trigger status type indicating the status of the trigger campaign.
     *               See {@link TriggerStatusType} for possible values.
     *
     * Example usage:
     * PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled)
     */
    public static void automatedNotification(TriggerStatusType status) {
        automatedNotification(status, null);
    }

    /**
     * Sends a trigger event for a specific campaign with the provided callback for handling the response.
     *
     * @param trigger  The {@link TriggerCampaign} object representing the campaign event to be triggered.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     *
     * Example usage:
     * val triggerCampaign = TriggerCampaign(
     *                 "name_of_campaign",
     *                 "event_name_of_trigger")
     *
     * PushEngage.sendTriggerEvent(triggerCampaign, object : PushEngageResponseCallback {
     *                 override fun onSuccess(responseObject: Any?) {
     *                     Toast.makeText(this@TriggerEntryActivity,"Send Trigger Alert Successfully", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage: String?) {
     *                     Toast.makeText(this@TriggerEntryActivity,errorMessage.toString(), Toast.LENGTH_LONG).show()
     *                 }
     *
     *             })
     */
    public static void sendTriggerEvent(TriggerCampaign trigger, PushEngageResponseCallback callback) {
        peManager.sendTriggerEvent(trigger, callback);
    }

    /**
     * Sends a trigger event for a specific campaign.
     *
     * @param trigger  The {@link TriggerCampaign} object representing the campaign event to be triggered.
     *
     * Example usage:
     * val triggerCampaign = TriggerCampaign(
     *                 "name_of_campaign",
     *                 "event_name_of_trigger")
     *
     * PushEngage.sendTriggerEvent(triggerCampaign)
     */
    public static void sendTriggerEvent(TriggerCampaign trigger) {
        sendTriggerEvent(trigger, null);
    }

    /**
     * Sends a goal event with the provided callback for handling the response.
     *
     * @param goal     The {@link Goal} object representing the goal to be tracked.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     *
     * Example usage:
     * val goal = Goal("revenue", 1,  10)
     * PushEngage.sendGoal(goal, object: PushEngageResponseCallback {
     *             override fun onSuccess(responseObject: Any?) {
     *                 Toast.makeText(this@GoalActivity, "Success", Toast.LENGTH_LONG).show()
     *             }
     *
     *             override fun onFailure(errorCode: Int?, errorMessage: String?) {
     *                 Toast.makeText(this@GoalActivity, "Failure", Toast.LENGTH_LONG).show()
     *             }
     *         })
     */
    public static void sendGoal(Goal goal, PushEngageResponseCallback callback) {
        peManager.sendGoal(goal, callback);
    }

    /**
     * Sends a goal event.
     *
     * @param goal The {@link Goal} object representing the completed goal to be tracked.
     *
     * Example usage:
     * val goal = Goal("revenue", 1,  10)
     * PushEngage.sendGoal(goal)
     */
    public static void sendGoal(Goal goal) {
        sendGoal(goal, null);
    }

    /**
     * Adds an alert to be triggered with the provided callback for handling the response.
     *
     * @param alert    The {@link TriggerAlert} object representing the alert to be added.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     *
     * Example usage:
     * val triggerAlert = TriggerAlert(
     *                 TriggerAlertType.inventory,
     *                 "product_id",
     *                 "link",
     *                 20.0)
     *
     * PushEngage.addAlert(triggerAlert, object : PushEngageResponseCallback {
     *                 override fun onSuccess(responseObject: Any?) {
     *                     Toast.makeText(this@AddAlertActivity, "Add Alert Successfully", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage: String?) {
     *                     Toast.makeText(this@AddAlertActivity, errorMessage, Toast.LENGTH_LONG).show()
     *                 }
     *
     *             })
     */
    public static void addAlert(TriggerAlert alert, PushEngageResponseCallback callback) {
        peManager.addAlert(alert, callback);
    }

    /**
     * Adds an alert to be triggered.
     *
     * @param alert The {@link TriggerAlert} object representing the alert to be added.
     *
     * Example usage:
     * val triggerAlert = TriggerAlert(
     *                 TriggerAlertType.inventory,
     *                 "product_id",
     *                 "link",
     *                 20.0)
     *
     * PushEngage.addAlert(triggerAlert)
     */
    public static void addAlert(TriggerAlert alert) {
        addAlert(alert, null);
    }

    /**
     * Initiates an API call to synchronize the client device with data from the server.
     * If successful, it triggers the subscription process or updating of subscriber data based
     * on the site status and notification preferences.
     */
    private static void callAndroidSync() {
        PELogger.debug("Sync for SiteKey = " + prefs.getSiteKey() + " called");
        if (PEUtilities.checkNetworkConnection(context)) {
            Call<AndroidSyncResponse> addRecordsResponseCall = RestClient.getBackendCdnClient(context).androidSync(prefs.getSiteKey());
            addRecordsResponseCall.enqueue(new Callback<AndroidSyncResponse>() {
                @Override
                public void onResponse(@NonNull Call<AndroidSyncResponse> call, @NonNull Response<AndroidSyncResponse> response) {
                    if (response.isSuccessful()) {
                        AndroidSyncResponse androidSyncResponse = response.body();
                        if (androidSyncResponse.getData().getSiteStatus().equalsIgnoreCase(PEConstants.ACTIVE)) {
                            /*
                              Updates preferences with the received server data.
                             */
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

                            /*
                              Checks if notifications are enabled and processes subscriber data accordingly.
                             */
                            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(context);
                            boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();
                            long longVal = areNotificationsEnabled ? 0 : 1;
                            prefs.setIsNotificationDisabled(longVal);
                            if (!areNotificationsEnabled) {
                                if (!prefs.getDeleteOnNotificationDisable()) {
                                    callAddSubscriberAPI();
                                } else {
                                    prefs.setIsSubscriberDeleted(true);
                                }
                            } else {
                                callAddSubscriberAPI();
                            }
                        } else {
                            /*
                              Handles server response indicating non-active site status.
                             */
                            prefs.setIsSubscriberDeleted(true);
                            PELogger.debug("Site Status = " + androidSyncResponse.getData().getSiteStatus());
                        }
                    } else {
                        siteSyncRetryCount++;
                        if (siteSyncRetryCount <= RETRY_COUNT) {
                            final Callback<AndroidSyncResponse> callback = this;
                            new Timer().schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    call.clone().enqueue(callback);
                                }
                            }, DELAY);//3 minutes delay
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<AndroidSyncResponse> call, @NonNull Throwable t) {
                    siteSyncRetryCount++;
                    if (siteSyncRetryCount <= RETRY_COUNT) {
                        final Callback<AndroidSyncResponse> callback = this;
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                call.clone().enqueue(callback);
                            }
                        }, DELAY);//3 minutes delay
                    }
                }
            });
        } else {
            siteSyncRetryCount++;
            if (siteSyncRetryCount <= RETRY_COUNT) {
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        callAndroidSync();
                    }
                }, DELAY);//3 minutes delay
            }
        }
    }

    private static Boolean isTablet() {
        return context.getResources().getBoolean(R.bool.is_tablet);
    }

    /**
     * Initiates an API call to add the client as a subscriber, sending necessary device and
     * app information to the server for registration.
     */
    public static void callAddSubscriberAPI() {
        String timeZone = PEUtilities.getTimeZone();
        String language = Locale.getDefault().getLanguage();
        String device = "";
         if (isTablet()) {
            device = PEConstants.TABLET;
        } else {
            device = PEConstants.MOBILE;
        }
        String deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        String deviceModel = android.os.Build.MODEL;
        String deviceManufacturer = android.os.Build.MANUFACTURER;
        String deviceVersion = Build.VERSION.RELEASE;
        String packageName = context.getPackageName();

        int width = Resources.getSystem().getDisplayMetrics().widthPixels;
        int height = Resources.getSystem().getDisplayMetrics().heightPixels;
        String screenSize = width + "*" + height;

        AddSubscriberRequest addSubscriberRequest = new AddSubscriberRequest();
        AddSubscriberRequest.Subscription subscription = addSubscriberRequest.new Subscription(prefs.getDeviceToken(), String.valueOf(prefs.getProjectId()));
        addSubscriberRequest = new AddSubscriberRequest(prefs.getSiteId(), subscription, PEConstants.ANDROID, device,
                deviceVersion, deviceModel, deviceManufacturer, timeZone, language, deviceName, screenSize, packageName, prefs.isNotificationDisabled());
        if (PEUtilities.checkNetworkConnection(context)) {
            Call<AddSubscriberResponse> addSubscriberResponseCall = RestClient.getBackendClient(context).addSubscriber(addSubscriberRequest, getSdkVersion(), String.valueOf(prefs.getEu()), String.valueOf(prefs.isGeoFetch()));
            addSubscriberResponseCall.enqueue(new Callback<AddSubscriberResponse>() {
                @Override
                public void onResponse(@NonNull Call<AddSubscriberResponse> call, @NonNull Response<AddSubscriberResponse> response) {
                    if (response.isSuccessful()) {
                        AddSubscriberResponse addSubscriberResponse = response.body();
                        prefs.setHash(addSubscriberResponse.getData().getSubscriberHash());
                        prefs.setIsSubscriberDeleted(false);
                        enableWeeklyDataSync();
                        enableDailyDataSync();
                    } else {
                        addSubscribeRetryCount++;
                        if (addSubscribeRetryCount <= RETRY_COUNT) {
                            final Callback<AddSubscriberResponse> callback = this;
                            new Timer().schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    call.clone().enqueue(callback);
                                }
                            }, DELAY);//3 minutes delay
                        } else {
                            String jsonStr = gson.toJson(response.body());
                            ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                            ErrorLogRequest.Data data = errorLogRequest.new Data("callAddSubscriberAPI", prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                            errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                            errorLogRequest.setName(PEConstants.RECORD_SUBSCRIPTION_FAILED);
                            errorLogRequest.setData(data);
                            PEUtilities.addLogs(context, TAG, errorLogRequest);
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<AddSubscriberResponse> call, @NonNull Throwable t) {
                    addSubscribeRetryCount++;
                    if (addSubscribeRetryCount <= RETRY_COUNT) {
                        final Callback<AddSubscriberResponse> callback = this;
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                call.clone().enqueue(callback);
                            }
                        }, DELAY);//3 minutes delay
                    } else {
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        ErrorLogRequest.Data data = errorLogRequest.new Data("callAddSubscriberAPI", prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                        errorLogRequest.setName(PEConstants.RECORD_SUBSCRIPTION_FAILED);
                        errorLogRequest.setData(data);
                        PEUtilities.addLogs(context, TAG, errorLogRequest);
                    }
                }
            });
        } else {
            addSubscribeRetryCount++;
            if (addSubscribeRetryCount <= RETRY_COUNT) {
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        callAddSubscriberAPI();
                    }
                }, DELAY);//3 minutes delay
            }
        }
    }

    /**
     * Retrieves the last notification payload data received by the client app.
     *
     * @return A {@code String} representing the last notification payload data stored in preferences.
     */
    private static String getLastNotificationPayload() {
        return prefs.getPayload();
    }

    /**
     * Enables a weekly synchronization task for subscriber data. This method schedules a periodic
     * work request to run the {@link WeeklySyncDataWorker} at regular intervals.
     * The worker performs data synchronization tasks related to subscribers on a weekly basis.
     */
    private static void enableWeeklyDataSync() {
        /*
          Network constraint to ensure the device is connected to the network for synchronization.
         */
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        /*
          Configures a periodic work request to run the WeeklySyncDataWorker every 7 days with a 1-hour
          flex interval. The worker performs synchronization tasks related to subscriber data.
         */
        PeriodicWorkRequest periodicSyncDataWork =
                new PeriodicWorkRequest.Builder(WeeklySyncDataWorker.class, 7, TimeUnit.DAYS, 1, // flex interval - worker will run somewhen within this period of time, but at the end of repeating interval
                        TimeUnit.HOURS)
                        .addTag(PEConstants.WEEKLY_SYNC_DATA)
                        .setConstraints(constraints)
                        // setting a backoff in case the work needs to retry
                        .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PEConstants.WEEKLY_SYNC_DATA,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSyncDataWork
        );
    }

    /**
     * Enables a daily synchronization task for checking notification permissions.
     * This method schedules a periodic work request to run the {@link DailySyncDataWorker}
     * at regular intervals. The worker checks notification permissions and performs related tasks daily.
     */
    private static void enableDailyDataSync() {
        /*
          Network constraint to ensure the device is connected to the network for synchronization.
         */
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        /*
          Configures a periodic work request to run the DailySyncDataWorker every 30 minutes with a
          5-minute flex interval. The worker checks notification permissions and performs related tasks daily.
         */
        PeriodicWorkRequest periodicSyncDataWork =
                new PeriodicWorkRequest.Builder(DailySyncDataWorker.class, 30, TimeUnit.MINUTES, 5,
                        TimeUnit.MINUTES)
                        .addTag(PEConstants.DAILY_SYNC_DATA)
                        .setConstraints(constraints)
                        // setting a backoff in case the work needs to retry
                        .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        .build();

        /*
          Enqueues the unique periodic work request with a specific tag and existing periodic work policy.
         */
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PEConstants.DAILY_SYNC_DATA,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSyncDataWork
        );

    }

    /**
     * Initiates an API call to retrieve subscriber details based on the provided list of values.
     *
     * @param values   A {@code List<String>} containing subscriber-related values for the API request.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks along with the subscriber data if successful.
     */
    public static void getSubscriberDetails(List<String> values, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> subscriberDetailsResponseCall = RestClient.getBackendClient(context).subscriberDetails(prefs.getHash(), values);
            subscriberDetailsResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse genericResponse = response.body();
                        if (callback != null && genericResponse != null)
                            callback.onSuccess(genericResponse.getData());
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Initiates an API call to retrieve subscriber attributes associated with the subscriber hash.
     *
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks along with the subscriber attributes if successful.
     */
    public static void getSubscriberAttributes(PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> getSubscriberAttributesResponseCall = RestClient.getBackendClient(context).getSubscriberAttributes(prefs.getHash());
            getSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse genericResponse = response.body();
                        if (callback != null && genericResponse != null)
                            callback.onSuccess(genericResponse.getData());
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, validationResult);
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Initiates an API call to delete specified subscriber attributes associated with the subscriber.
     * This method allows deletion of multiple attributes at once.
     *
     * @param values A {@code List<String>} containing attribute names to be deleted.
     */
    public static void deleteSubscriberAttributes(List<String> values) {
        deleteSubscriberAttributes(values, null);
    }

    /**
     * Initiates an API call to delete specified subscriber attributes associated with the subscriber.
     * This method allows deletion of multiple attributes at once and provides a callback for handling
     * the API response asynchronously.
     *
     * @param values   A {@code List<String>} containing attribute names to be deleted.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     */
    public static void deleteSubscriberAttributes(List<String> values, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> deleteSubscriberAttributesResponseCall = RestClient.getBackendClient(context).deleteSubscriberAttributes(prefs.getHash(), values);
            deleteSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Updates attributes of a subscriber. If an attribute with the specified key already exists, the existing value will be replaced.
     * Does not provide a callback for API response handling.
     *
     * @param obj A {@link JSONObject} containing the subscriber attributes to be added.
     */
    public static void addSubscriberAttributes(JSONObject obj) {
        addSubscriberAttributes(obj, null);
    }

    /**
     * Updates attributes of a subscriber. If an attribute with the specified key already exists, the existing value will be replaced.
     * Provides a callback for handling the API response asynchronously.
     *
     * @param obj      A {@link JSONObject} containing the subscriber attributes to be added.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     */
    public static void addSubscriberAttributes(JSONObject obj, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<NetworkResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(context).addAttributes(prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Sets attributes of a subscriber replacing any previously associated attributes.
     * This method allows updating multiple attributes at once and does not provide a callback for API response handling.
     *
     * @param obj A {@link JSONObject} containing the updated subscriber attributes.
     */
    public static void setSubscriberAttributes(JSONObject obj) {
        setSubscriberAttributes(obj, null);
    }

    /**
     * Sets attributes of a subscriber replacing any previously associated attributes.
     * This method allows updating multiple attributes at once and provides a callback for handling
     * the API response asynchronously.
     *
     * @param obj      A {@link JSONObject} containing the updated subscriber attributes.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     */
    public static void setSubscriberAttributes(JSONObject obj, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<NetworkResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(context).setAttributes(prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Initiates an API call to associate a profile ID with the subscriber.
     * This method allows adding a profile ID and does not provide a callback for API response handling.
     *
     * @param profileId A {@link String} representing the profile ID to be associated with the subscriber.
     */
    public static void addProfileId(String profileId) {
        addProfileId(profileId, null);
    }

    /**
     * Initiates an API call to associate a profile ID with the subscriber.
     * This method allows adding a profile ID and provides a callback for handling
     * the API response asynchronously.
     *
     * @param profileId A {@link String} representing the profile ID to be associated with the subscriber.
     * @param callback  A callback interface to handle API response asynchronously.
     *                  Implement the {@link PushEngageResponseCallback} interface to receive
     *                  success or failure callbacks.
     */
    public static void addProfileId(String profileId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddProfileIdRequest addProfileIdRequest = new AddProfileIdRequest(
                    prefs.getHash(), profileId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> addProfileIdResponseCall = RestClient.getBackendClient(context).addProfileId(addProfileIdRequest);
            addProfileIdResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Initiates an API call to add segments.
     * This method allows the addition of subscriber to a segment.
     *
     * @param segmentId A list of segment IDs to be added.
     */
    public static void addSegment(List<String> segmentId) {
        addSegment(segmentId, null);
    }

    /**
     * Initiates an API call to add segments with a callback for handling the response.
     * This method allows the addition of subscriber to a segment and provides a callback
     * mechanism to handle the success or failure response from the server.
     *
     * @param segmentId A list of segment IDs to be added.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     */
    public static void addSegment(List<String> segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddSegmentRequest addSegmentRequest = new AddSegmentRequest(prefs.getHash(), segmentId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> addSegmentResponseCall = RestClient.getBackendClient(context).addSegments(addSegmentRequest);
            addSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }

                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Initiates an API call to remove segments.
     * This method allows the removal of specified segments for a subscriber. It does not provide a
     * callback for handling the success or failure response from the server.
     *
     * @param segmentId A list of segment IDs to be removed. Must not be null.
     */
    public static void removeSegment(List<String> segmentId) {
        removeSegment(segmentId, null);
    }

    /**
     * Initiates an API call to remove segments with a provided callback for handling the response.
     * This method allows the removal of specified segments for a subscriber. It provides a callback
     * mechanism to handle the success or failure response from the server.
     *
     * @param segmentId A list of segment IDs to be removed.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     */
    public static void removeSegment(List<String> segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            RemoveSegmentRequest removeSegmentRequest = new RemoveSegmentRequest(
                    prefs.getHash(), segmentId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> removeSegmentResponseCall = RestClient.getBackendClient(context).removeSegments(removeSegmentRequest);
            removeSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * Initiates an API call to add dynamic segments with a provided list of segments and a callback for handling the response.
     * This method allows the addition of dynamic segments to a subscriber's profile. Dynamic segments are defined by a list
     * of segment objects containing specific criteria.
     *
     * @param segments A list of segment objects representing dynamic segments to be added. Must not be null.
     */
    public static void addDynamicSegment(List<AddDynamicSegmentRequest.Segment> segments) {
        addDynamicSegment(segments, null);
    }

    /**
     * Initiates an API call to add dynamic segments with a provided list of segments and a callback for handling the response.
     * This method allows the addition of dynamic segments to a subscriber's profile. Dynamic segments are defined by a list
     * of segment objects containing specific criteria. It provides a callback mechanism to handle the success or failure
     * response from the server.
     *
     * @param segments A list of segment objects representing dynamic segments to be added. Must not be null.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to receive
     *                 success or failure callbacks.
     */
    public static void addDynamicSegment(List<AddDynamicSegmentRequest.Segment> segments, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddDynamicSegmentRequest addDynamicSegmentRequest = new AddDynamicSegmentRequest(prefs.getHash(), prefs.getSiteId(), PEConstants.ANDROID, segments);
            Call<NetworkResponse> addDynamicSegmentResponseCall = RestClient.getBackendClient(context).addDynamicSegments(addDynamicSegmentRequest);
            addDynamicSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to get Segment Hash Array
     *
     * @param segmentId
     * @param callback
     */
    private static void getSegmentHashArray(String segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            SegmentHashArrayRequest segmentHashArrayRequest = new SegmentHashArrayRequest(prefs.getHash(), prefs.getSiteId(), segmentId);
            Call<NetworkResponse> segmentHashArrayResponseCall = RestClient.getBackendClient(context).getSegmentHashArray(segmentHashArrayRequest);
            segmentHashArrayResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to check Subscriber Hash
     *
     * @param callback
     */
    private static void checkSubscriberHash(PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> checkSubscriberHashResponseCall = RestClient.getBackendClient(context).checkSubscriberHash(prefs.getHash());
            checkSubscriberHashResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call, @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"), jsonObj.getString("error_message"));
                            } catch (Exception e) {
                                if (callback != null)
                                    callback.onFailure(response.code(), context.getString(R.string.server_error));
                            }
                        } else {
                            if (callback != null)
                                callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<NetworkResponse> call, @NonNull Throwable t) {
                    if (callback != null)
                        callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            if (callback != null)
                callback.onFailure(400, validationResult);
        }
    }

}


