package com.pushengage.pushengage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Build;
import android.telephony.TelephonyManager;
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
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest;
import com.pushengage.pushengage.model.request.AddProfileIdRequest;
import com.pushengage.pushengage.model.request.AddSegmentRequest;
import com.pushengage.pushengage.model.request.AddSubscriberRequest;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.request.RecordsRequest;
import com.pushengage.pushengage.model.request.RemoveSegmentRequest;
import com.pushengage.pushengage.model.request.SegmentHashArrayRequest;
import com.pushengage.pushengage.model.request.UpdateTriggerStatusRequest;
import com.pushengage.pushengage.model.response.AddSubscriberResponse;
import com.pushengage.pushengage.model.response.AndroidSyncResponse;
import com.pushengage.pushengage.model.response.GenricResponse;
import com.pushengage.pushengage.model.response.RecordsResponse;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PushEngage {

    private static Context context;
    private static String TAG = "PushEngage";
    private static PEPrefs prefs;
    private static final int RETRY_COUNT = 3;
    private static int addSubscribeRetryCount = 0, siteSyncRetryCount = 0;
    private static Gson gson = new Gson();
    private static final int DELAY = 180000;
    public static final String City = "city",
            Country = "country",
            Device = "device",
            DeviceType = "device_type",
            ProfileId = "profile_id",
            Segments = "segments",
            State = "state",
            Timezone = "timezone",
            TsCreated = "ts_created";
    private static UpdateTriggerStatusRequest updateTriggerStatusRequest;

    /**
     * Library Initialization
     *
     * @param context
     * @param siteKey
     */
    private PushEngage(Context context, String siteKey) {
        // Library Initialized here
        this.context = context;
        prefs = new PEPrefs(context);
        prefs.setSiteKey(siteKey);
        registerNetworkReceiver();
        addSubscribeRetryCount = 0;
        siteSyncRetryCount = 0;
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
     * Registering Network Receiver for Offline support.
     */
    private void registerNetworkReceiver() {
        BroadcastReceiver br = new NetworkChangeReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(br, filter);
    }

    /**
     * @return Current SDK version
     */
    public static String getSdkVersion() {
        return "1.0";
    }

    /**
     * Sets the small icon for the notification from the client App.
     *
     * @param resourceName
     */
    public static void setSmallIconResource(String resourceName) {
        prefs.setSmallIconResource(resourceName);
    }

    /**
     * @return Device Token
     */
    private static String getDeviceToken() {
        return prefs.getDeviceToken();
    }

    /**
     * @return Device Hash generated based on the Device Token
     */
    public static String getDeviceTokenHash() {
        return prefs.getHash();
    }

    /**
     * Initialization of Firebase Cloud Messaging
     */
    public static void subscribe() {
        //Enables or disables auto-initialization of Firebase Cloud Messaging.
        //When enabled, Firebase Cloud Messaging generates a registration token on app startup if there is no valid one and periodically sends data to the Firebase backend to validate the token.
        //This setting is persisted across app restarts and overrides the setting specified in your manifest.
        FirebaseApp.initializeApp(context);
        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull Task<String> task) {
                        if (!task.isSuccessful()) {
                            return;
                        } else {
                            // Get new FCM registration token
                            String token = task.getResult();
                            Log.d(TAG, token);
                            if (!prefs.getDeviceToken().equals(token) || prefs.getSiteId() == 0) {
                                prefs.setDeviceToken(token);
                                callAndroidSync();
                            }
                        }
                    }
                });
    }

    /**
     * Trigger Add Records
     *
     * @param callback
     * @param campaignName
     * @param eventName
     * @param title
     * @param message
     * @param notificationUrl
     * @param notificationImage
     * @param bigImage
     */
    private static void triggerAddRecords(PushEngageResponseCallback callback, String campaignName, String eventName, Map<String, String> title, Map<String, String> message, Map<String, String> notificationUrl, Map<String, String> notificationImage, Map<String, String> bigImage) {
        RecordsRequest recordsRequest = new RecordsRequest();
        RecordsRequest.Data data = recordsRequest.new Data(campaignName, eventName, title, message, notificationUrl, notificationImage, bigImage, prefs.getHash(), prefs.getSiteId());
        recordsRequest.setData(data);
        recordsRequest.setPartitionKey(prefs.getHash());
        callAddRecords(recordsRequest, callback);
    }

    /**
     * Trigger Add Records with checkout Data
     *
     * @param callback
     * @param campaignName
     * @param eventName
     * @param title
     * @param message
     * @param notificationUrl
     * @param notificationImage
     * @param bigImage
     * @param checkoutData
     */
    private static void triggerAddRecords(PushEngageResponseCallback callback, String campaignName, String eventName, Map<String, String> title, Map<String, String> message, Map<String, String> notificationUrl, Map<String, String> notificationImage, Map<String, String> bigImage, Map<String, String> checkoutData) {
        RecordsRequest recordsRequest = new RecordsRequest();
        RecordsRequest.Data data = recordsRequest.new Data(campaignName, eventName, title, message, notificationUrl, notificationImage, bigImage, prefs.getHash(), prefs.getSiteId(), checkoutData);
        recordsRequest.setData(data);
        recordsRequest.setPartitionKey(prefs.getHash());
        callAddRecords(recordsRequest, callback);
    }

    /**
     * API Call to sync the device with Data from server
     */
    private static void callAndroidSync() {
        Log.d(TAG, " SiteKey = " + prefs.getSiteKey());
        if (PEUtilities.checkNetworkConnection(context)) {
            Call<AndroidSyncResponse> addRecordsResponseCall = RestClient.getBackendCdnClient(context).androidSync(prefs.getSiteKey());
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
                            prefs.setIsSubscriberDeleted(true);
                            Log.d(TAG, "Site Status = " + androidSyncResponse.getData().getSiteStatus());
                        }
                    } else {
                        Log.e(TAG, "API Failure");
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
                    Log.e(TAG, "API Failure");
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

    /**
     * API call to Add this as Subscriber
     */
    public static void callAddSubscriberAPI() {
        String timeZone = PEUtilities.getTimeZone();
        String language = Locale.getDefault().getLanguage();
        String device = "";
        TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (Objects.requireNonNull(manager).getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
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
                        enableWeeklyDataSync();
                        enableDailyDataSync();
                        Log.d(TAG, addSubscriberResponse.getData().getSubscriberHash());
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
                            Log.e(TAG, "API Failure");
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
                    Log.e(TAG, "API Failure");
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
     * API request to add Trigger records
     *
     * @param recordsRequest
     * @param callback
     */
    private static void callAddRecords(RecordsRequest recordsRequest, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<RecordsResponse> addRecordsResponseCall = RestClient.getTriggerClient(context).records(recordsRequest);
            addRecordsResponseCall.enqueue(new Callback<RecordsResponse>() {
                @Override
                public void onResponse(@NonNull Call<RecordsResponse> call, @NonNull Response<RecordsResponse> response) {
                    if (response.isSuccessful()) {
                        RecordsResponse addRecordsResponse = response.body();
                        callback.onSuccess(null);
                    } else {
                        callback.onFailure(response.code(), response.message());
                        Log.e(TAG, "API Failure");
                    }
                }

                @Override
                public void onFailure(@NonNull Call<RecordsResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }


    }

    /**
     * @return Last notification payload data
     */
    private static String getLastNotificationPayload() {
        return prefs.getPayload();
    }

    /**
     * Enables the weekly sync for Subscriber data.
     */
    private static void enableWeeklyDataSync() {
        // Create Network constraint
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();


        PeriodicWorkRequest periodicSyncDataWork =
                new PeriodicWorkRequest.Builder(WeeklySyncDataWorker.class, 7, TimeUnit.DAYS, 1, // flex interval - worker will run somewhen within this period of time, but at the end of repeating interval
                        TimeUnit.HOURS)
                        .addTag(PEConstants.WEEKLY_SYNC_DATA)
                        .setConstraints(constraints)
                        // setting a backoff on case the work needs to retry
                        .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        .build();
        WorkManager.getInstance().enqueueUniquePeriodicWork(
                PEConstants.WEEKLY_SYNC_DATA,
                ExistingPeriodicWorkPolicy.KEEP, //Existing Periodic Work policy
                periodicSyncDataWork //work request
        );

    }

    /**
     * Enables Daily sync for checking notification permissions.
     */
    private static void enableDailyDataSync() {
        // Create Network constraint
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();


        PeriodicWorkRequest periodicSyncDataWork =
                new PeriodicWorkRequest.Builder(DailySyncDataWorker.class, 30, TimeUnit.MINUTES, 5, // flex interval - worker will run somewhere within this period of time, but at the end of repeating interval
                        TimeUnit.MINUTES)
                        .addTag(PEConstants.DAILY_SYNC_DATA)
                        .setConstraints(constraints)
                        // setting a backoff on case the work needs to retry
                        .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        .build();
        WorkManager.getInstance().enqueueUniquePeriodicWork(
                PEConstants.DAILY_SYNC_DATA,
                ExistingPeriodicWorkPolicy.KEEP, //Existing Periodic Work policy
                periodicSyncDataWork //work request
        );

    }

    /**
     * API call to get subscriber Details
     *
     * @param values
     * @param callback
     */
    public static void getSubscriberDetails(List<String> values, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<GenricResponse> subscriberDetailsResponseCall = RestClient.getBackendClient(context).subscriberDetails(prefs.getHash(), values);
            subscriberDetailsResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        GenricResponse genricResponse = response.body();
                        callback.onSuccess(genricResponse.getData());
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to get Subscriber Attributes
     *
     * @param callback
     */
    public static void getSubscriberAttributes(PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<GenricResponse> getSubscriberAttributesResponseCall = RestClient.getBackendClient(context).getSubscriberAttributes(prefs.getHash());
            getSubscriberAttributesResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        GenricResponse genricResponse = response.body();
                        callback.onSuccess(genricResponse.getData());
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, validationResult);
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to delete Subscriber Attributes
     *
     * @param values
     * @param callback
     */
    public static void deleteSubscriberAttributes(List<String> values, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<GenricResponse> deleteSubscriberAttributesResponseCall = RestClient.getBackendClient(context).deleteSubscriberAttributes(prefs.getHash(), values);
            deleteSubscriberAttributesResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to add Subscriber Attributes
     *
     * @param obj
     * @param callback
     */
    public static void addSubscriberAttributes(JSONObject obj, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<GenricResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(context).addAttributes(prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to Set Subscriber Attributes
     *
     * @param obj
     * @param callback
     */
    public static void setSubscriberAttributes(JSONObject obj, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<GenricResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(context).setAttributes(prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to add ProfileId
     *
     * @param profileId
     * @param callback
     */
    public static void addProfileId(String profileId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddProfileIdRequest addProfileIdRequest = new AddProfileIdRequest(
                    prefs.getHash(), profileId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<GenricResponse> addProfileIdResponseCall = RestClient.getBackendClient(context).addProfileId(addProfileIdRequest);
            addProfileIdResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to add Segments
     *
     * @param segmentId
     * @param callback
     */
    public static void addSegment(List<String> segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddSegmentRequest addSegmentRequest = new AddSegmentRequest(prefs.getHash(), segmentId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<GenricResponse> addSegmentResponseCall = RestClient.getBackendClient(context).addSegments(addSegmentRequest);
            addSegmentResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to remove Segments
     *
     * @param segmentId
     * @param callback
     */
    public static void removeSegment(List<String> segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            RemoveSegmentRequest removeSegmentRequest = new RemoveSegmentRequest(
                    prefs.getHash(), segmentId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<GenricResponse> removeSegmentResponseCall = RestClient.getBackendClient(context).removeSegments(removeSegmentRequest);
            removeSegmentResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to add Dynamic Segments
     *
     * @param segments
     * @param callback
     */
    public static void addDynamicSegment(List<AddDynamicSegmentRequest.Segment> segments, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddDynamicSegmentRequest addDynamicSegmentRequest = new AddDynamicSegmentRequest(prefs.getHash(), prefs.getSiteId(), PEConstants.ANDROID, segments);
            Call<GenricResponse> addDynamicSegmentResponseCall = RestClient.getBackendClient(context).addDynamicSegments(addDynamicSegmentRequest);
            addDynamicSegmentResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
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
            Call<GenricResponse> segmentHashArrayResponseCall = RestClient.getBackendClient(context).getSegmentHashArray(segmentHashArrayRequest);
            segmentHashArrayResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
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
            Call<GenricResponse> checkSubscriberHashResponseCall = RestClient.getBackendClient(context).checkSubscriberHash(prefs.getHash());
            checkSubscriberHashResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

    /**
     * API call to update Trigger Status
     *
     * @param triggerStatus
     * @param callback
     */
    private static void updateTriggerStatus(Integer triggerStatus, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            UpdateTriggerStatusRequest updateTriggerStatusRequest =
                    new UpdateTriggerStatusRequest(prefs.getSiteId(), prefs.getHash(), triggerStatus);
            updateTriggerStatusRequest.setDeviceTokenHash(prefs.getHash());
            Call<GenricResponse> updateTriggerStatusResponseCall = RestClient.getBackendClient(context).updateTriggerStatus(updateTriggerStatusRequest);
            updateTriggerStatusResponseCall.enqueue(new Callback<GenricResponse>() {
                @Override
                public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        Log.e(TAG, "API Failure");
                        GenricResponse genricResponse = response.body();
                        if (genricResponse != null && genricResponse.getError() != null) {
                            callback.onFailure(genricResponse.getError().getCode(), genricResponse.getError().getMessage());
                        } else {
                            callback.onFailure(response.code(), context.getString(R.string.server_error));
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "API Failure");
                    callback.onFailure(400, t.getMessage());
                }
            });
        } else {
            callback.onFailure(400, validationResult);
        }
    }

}
