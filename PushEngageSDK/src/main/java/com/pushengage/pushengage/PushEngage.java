package com.pushengage.pushengage;

import static kotlin.io.TextStreamsKt.readText;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.activity.ComponentActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback;
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback;
import com.pushengage.pushengage.DataWorker.DailySyncDataWorker;
import com.pushengage.pushengage.DataWorker.WeeklySyncDataWorker;
import com.pushengage.pushengage.permissionhandling.PEPermissionFragment;

import androidx.fragment.app.FragmentActivity;
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
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest;
import com.pushengage.pushengage.model.request.UpdateTriggerStatusRequest;
import com.pushengage.pushengage.model.response.AddSubscriberResponse;
import com.pushengage.pushengage.model.response.AndroidSyncResponse;
import com.pushengage.pushengage.model.response.NetworkResponse;
import com.pushengage.pushengage.model.response.RecordsResponse;

import org.json.JSONObject;

import java.util.ArrayList;
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
import androidx.appcompat.app.AppCompatActivity;

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
     * Initializes the PushEngage library with the specified Android application
     * context
     * and site key.
     *
     * @param context The Android application context in which the library will
     *                operate.
     * @param siteKey The unique identifier for the PushEngage site.
     */
    private PushEngage(Context context, String siteKey) {
        PushEngage.context = context;
        prefs = new PEPrefs(context);
        prefs.setSiteKey(siteKey);
        registerNetworkReceiver();
        addSubscribeRetryCount = 0;
        siteSyncRetryCount = 0;
        peManager = new PEManager(context, prefs);
        if (TextUtils.isEmpty(prefs.getHash()) &&
                "granted".equals(getNotificationPermissionStatus()) &&
                !prefs.isManuallyUnsubscribed()) {
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
     * Registers a network receiver to monitor network connectivity changes. This
     * enables
     * the library to provide offline support and handle network-related events.
     * When network connectivity changes, the registered
     * {@link NetworkChangeReceiver}
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
     *         This version string is defined in the {@link PEConstants} class.
     */
    public static String getSdkVersion() {
        return PEConstants.SDK_VERSION;
    }

    /**
     * Sets the resource name of the small icon used for notifications generated by
     * the
     * client app. The small icon appears in the status bar when a notification is
     * displayed.
     * Note: It is recommended to set a valid resource name to ensure proper display
     * of
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
     * @return A {@code String} representing the device token obtained from the
     *         preferences manager.
     */
    private static String getDeviceToken() {
        return prefs.getDeviceToken();
    }

    /**
     * Retrieves the device hash generated based on the device token associated with
     * the client app.
     *
     * @return A String representing the device token associated with the client
     *         app.
     */
    public static String getDeviceTokenHash() {
        return prefs.getHash();
    }

    /**
     * Initializes Firebase Cloud Messaging for push notifications.
     * For Android 13 and above, the client should call this method once after
     * granting
     * notification permission. This method enables auto-initialization of Firebase
     * Cloud Messaging,
     * generates a registration token on app startup (if there is no valid one), and
     * periodically
     * sends data to the Firebase backend to validate the token.
     */
    public static void subscribe() {
        FirebaseApp.initializeApp(context);
        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        if (isFirebaseApiCallInProgress) {
            return;
        }
        isFirebaseApiCallInProgress = true;
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull Task<String> task) {
                        isFirebaseApiCallInProgress = false;
                        if (!task.isSuccessful()) {
                            PELogger.error("Firebase token retrieval failed", task.getException());
                            return;
                        } else {
                            // Get new FCM registration token
                            String token = task.getResult();
                            Log.d(TAG, token);
                            if (!prefs.getDeviceToken().equals(token) || prefs.getSiteId() == 0
                                    || prefs.getHash().isEmpty()) {
                                prefs.setDeviceToken(token);
                                callAndroidSync();
                            }
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        isFirebaseApiCallInProgress = false;
                        PELogger.error("Firebase token retrieval failed with exception", e);
                    }
                });
    }

    /**
     * Requests notification permission from the user. For Android 13 (API 33) and
     * above,
     * this will show the system permission dialog. For older versions, the
     * permission is
     * automatically granted and the callback will be invoked with granted=true.
     * <p>
     * This method works with both ComponentActivity (Compose) and FragmentActivity.
     * <p>
     * When permission is granted, the SDK automatically calls
     * PushEngage.subscribe()
     * for you, so you don't need to call it manually in the callback.
     * 
     * @param activity The {@code ComponentActivity} (works with both Compose and
     *                 traditional Activities)
     * @param callback The {@code PushEngagePermissionCallback} callback to be
     *                 invoked with the permission result
     * 
     * @see PushEngagePermissionCallback#onPermissionResult(boolean, Error)
     * 
     *      Example usage:
     *
     *      // Works with ComponentActivity (Compose)
     *      PushEngage.requestNotificationPermission(this, new PushEngagePermissionCallback() {
     *          {@literal @}Override
     *          public void onPermissionResult(boolean granted, Error error) {
     *              if (granted) {
     *                  Log.d("Permission", "User is now subscribed!");
     *              } else {
     *                  // Handle permission denied
     *                  Log.d("Permission", "Permission denied: " + error.getMessage());
     *              }
     *          }
     *      });
     *
     */
    public static void requestNotificationPermission(ComponentActivity activity,
            PushEngagePermissionCallback callback) {
        PEPermissionFragment.requestPermission(activity, callback);
    }

    /**
     * Get the current notification permission status
     *
     * Use this method to retrieve the current notification permission status for
     * the application.
     * This method returns the permission status synchronously as a string.
     *
     * @return A {@code String} indicating the current notification permission
     *         state:
     *         - {@code "granted"}: The application is authorized to post user
     *         notifications
     *         - {@code "denied"}: The application is not authorized to post user
     *         notifications
     *
     *         Example usage:
     *
     *         String permissionStatus =
     *         PushEngage.getNotificationPermissionStatus();
     *         switch (permissionStatus) {
     *         case "granted":
     *         Log.d("Permission", "Notifications are allowed");
     *         break;
     *         case "denied":
     *         Log.d("Permission", "Notifications are denied");
     *         break;
     *         default:
     *         Log.d("Permission", "Unknown permission status");
     *         break;
     *         }
     */
    public static String getNotificationPermissionStatus() {
        if (context == null) {
            return "denied";
        }

        // Check if notifications are enabled at the system level
        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(context);
        return notificationManagerCompat.areNotificationsEnabled() ? "granted" : "denied";
    }

    /**
     * Get the current subscription status for push notifications
     *
     * This method checks if the user is subscribed to the push notification
     * service.
     *
     * @param callback The callback to be invoked with the subscription status
     *                 result
     *                 - {@code true}: User is subscribed to push notifications
     *                 - {@code false}: User is not subscribed (unsubscribed or
     *                 never subscribed)
     *
     *                 Example usage:
     *
     *                 PushEngage.getSubscriptionStatus(new
     *                 PushEngageResponseCallback() {
     *                 {@literal @}Override
     *                 public void onSuccess(Object result) {
     *                 Boolean isSubscribed = (Boolean) result;
     *                 if (isSubscribed) {
     *                 Log.d("Subscription", "User is subscribed");
     *                 } else {
     *                 Log.d("Subscription", "User is not subscribed");
     *                 }
     *                 }
     *
     *                 {@literal @}Override
     *                 public void onFailure(Integer errorCode, String errorMessage)
     *                 {
     *                 Log.e("Subscription", "Error checking subscription: " +
     *                 errorMessage);
     *                 }
     *                 });
     */
    public static void getSubscriptionStatus(PushEngageResponseCallback callback) {
        if (callback == null) {
            PELogger.error("Callback cannot be null for getSubscriptionStatus", null);
            return;
        }

        if (context == null) {
            callback.onFailure(null, "SDK not initialized");
            return;
        }

        if (prefs == null) {
            callback.onFailure(null, "SDK preferences not initialized");
            return;
        }

        try {
            String siteStatus = prefs.getSiteStatus();
            String notificationPermissionStatus = getNotificationPermissionStatus();
            boolean isManuallyUnsubscribed = prefs.isManuallyUnsubscribed();
            boolean isSubscriberDeleted = prefs.isSubscriberDeleted();
            String subscriberHash = prefs.getHash();

            // Check if site is active
            if (!PEConstants.ACTIVE.equalsIgnoreCase(siteStatus)) {
                PELogger.debug("getSubscriptionStatus - site not active");
                callback.onFailure(null, PEConstants.SITE_NOT_ACTIVE);
                return;
            }

            // Check if manually unsubscribed
            if (isManuallyUnsubscribed) {
                PELogger.debug("getSubscriptionStatus - manually unsubscribed");
                callback.onSuccess(false);
                return;
            }

            // Check if subscriber hash exists
            if (TextUtils.isEmpty(subscriberHash)) {
                PELogger.debug("getSubscriptionStatus - no subscriber hash, not subscribed yet");
                callback.onSuccess(false);
                return;
            }

            // Check notification permission status
            if ("denied".equals(notificationPermissionStatus)) {
                PELogger.debug("getSubscriptionStatus - notification permission denied");
                callback.onFailure(null, "Notification permission not granted");
                return;
            }

            // If local data indicates subscribed (has hash, permission granted, not
            // manually unsubscribed, not deleted)
            if (!isSubscriberDeleted && "granted".equals(notificationPermissionStatus)) {
                PELogger.debug("getSubscriptionStatus - local data indicates subscribed, returning true");
                callback.onSuccess(true);
                return;
            }

            List<String> fields = new ArrayList<>();
            fields.add("has_unsubscribed");
            fields.add("notification_disabled");

            getSubscriberDetails(fields, new PushEngageResponseCallback() {
                @Override
                public void onSuccess(Object responseObject) {
                    try {
                        if (responseObject instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> subscriberData = (Map<String, Object>) responseObject;

                            // User is subscribed only when both hasUnsubscribed = 0 AND
                            // notification_disabled = 0
                            Object hasUnsubscribedObj = subscriberData.get("has_unsubscribed");
                            Object notificationDisabledObj = subscriberData.get("notification_disabled");

                            int hasUnsubscribed = hasUnsubscribedObj != null ? ((Number) hasUnsubscribedObj).intValue()
                                    : 0;
                            int notificationDisabled = notificationDisabledObj != null
                                    ? ((Number) notificationDisabledObj).intValue()
                                    : 0;

                            boolean isSubscribed = (hasUnsubscribed == 0) && (notificationDisabled == 0);

                            PELogger.debug("getSubscriptionStatus - hasUnsubscribed: " + hasUnsubscribed +
                                    ", notificationDisabled: " + notificationDisabled +
                                    ", result: isSubscribed: " + isSubscribed);

                            callback.onSuccess(isSubscribed);
                        } else {
                            PELogger.error("getSubscriptionStatus - unexpected response format", null);
                            callback.onFailure(null, "Unexpected response format");
                        }
                    } catch (Exception e) {
                        PELogger.error("getSubscriptionStatus - error parsing API response", e);
                        callback.onFailure(null, "Error parsing subscription data");
                    }
                }

                @Override
                public void onFailure(Integer errorCode, String errorMessage) {
                    PELogger.error("getSubscriptionStatus - API call failed: " + errorMessage, null);
                    callback.onFailure(errorCode, errorMessage);
                }
            });

        } catch (Exception e) {
            PELogger.error("getSubscriptionStatus - unexpected error", e);
            callback.onFailure(null, "Failed to check subscription status: " + e.getMessage());
        }
    }

    /**
     * Get the current subscription notification status
     *
     * This method checks if the user is both subscribed to push notifications AND
     * has system notification permission granted. This represents the complete
     * ability to receive push notifications. The implementation matches iOS logic
     * by first checking subscription status and then notification permissions.
     *
     * @param callback The callback to be invoked with the subscription notification
     *                 status
     *                 - {@code true}: User can receive notifications (subscribed
     *                 AND permission granted)
     *                 - {@code false}: User cannot receive notifications (not
     *                 subscribed or permission denied)
     *
     *                 Example usage:
     *
     *                 PushEngage.getSubscriptionNotificationStatus(new
     *                 PushEngageResponseCallback() {
     *                 {@literal @}Override
     *                 public void onSuccess(Object result) {
     *                 Boolean canReceiveNotifications = (Boolean) result;
     *                 if (canReceiveNotifications) {
     *                 Log.d("Subscription", "User can receive notifications");
     *                 } else {
     *                 Log.d("Subscription", "User cannot receive notifications");
     *                 }
     *                 }
     *
     *                 {@literal @}Override
     *                 public void onFailure(Integer errorCode, String errorMessage)
     *                 {
     *                 Log.e("Subscription", "Error checking notification status: "
     *                 + errorMessage);
     *                 }
     *                 });
     */
    public static void getSubscriptionNotificationStatus(PushEngageResponseCallback callback) {
        if (callback == null) {
            PELogger.error("Callback cannot be null for getSubscriptionNotificationStatus", null);
            return;
        }

        if (context == null) {
            callback.onFailure(null, "SDK not initialized");
            return;
        }

        // First check subscription status (matches iOS implementation)
        getSubscriptionStatus(new PushEngageResponseCallback() {
            @Override
            public void onSuccess(Object responseObject) {
                try {
                    boolean isSubscribed = (Boolean) responseObject;

                    // Check notification permission status
                    String permissionStatus = getNotificationPermissionStatus();
                    boolean hasNotificationPermission = "granted".equals(permissionStatus);

                    // User can receive notifications only if subscribed AND has permission
                    boolean canReceiveNotifications = isSubscribed && hasNotificationPermission;

                    PELogger.debug("getSubscriptionNotificationStatus - isSubscribed: " + isSubscribed +
                            ", permissionStatus: " + permissionStatus +
                            ", canReceiveNotifications: " + canReceiveNotifications);

                    callback.onSuccess(canReceiveNotifications);
                } catch (Exception e) {
                    PELogger.error("getSubscriptionNotificationStatus - error processing subscription status", e);
                    callback.onFailure(null, "Error processing subscription status: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(Integer errorCode, String errorMessage) {
                PELogger.error("getSubscriptionNotificationStatus - subscription check failed: " + errorMessage, null);
                callback.onFailure(errorCode, errorMessage);
            }
        });
    }

    /**
     * Get Subscriber ID
     *
     * Use this method to retrieve the unique subscriber ID for a user. PushEngage
     * generates this ID for every user
     * based on their subscription data. Sometimes, this ID is referred to as the
     * 'subscriber_hash'. The subscriber ID
     * remains consistent unless there's a change in the user's subscription. If the
     * user is not subscribed, it will return null.
     *
     * @param callback The callback to be invoked with the subscriber ID result
     *                 - {@code String}: The subscriber ID if the user is subscribed
     *                 and has a valid hash
     *                 - {@code null}: If the user is not subscribed or doesn't have
     *                 a valid hash
     *
     *                 Example usage:
     *
     *                 PushEngage.getSubscriberId(new PushEngageResponseCallback() {
     *                 {@literal @}Override
     *                 public void onSuccess(Object result) {
     *                 String subscriberId = (String) result;
     *                 if (subscriberId != null) {
     *                 Log.d("Subscriber", "Subscriber ID: " + subscriberId);
     *                 } else {
     *                 Log.d("Subscriber", "User is not subscribed");
     *                 }
     *                 }
     *
     *                 {@literal @}Override
     *                 public void onFailure(Integer errorCode, String errorMessage)
     *                 {
     *                 Log.e("Subscriber", "Error getting subscriber ID: " +
     *                 errorMessage);
     *                 }
     *                 });
     */
    public static void getSubscriberId(PushEngageResponseCallback callback) {
        if (callback == null) {
            PELogger.error("Callback cannot be null for getSubscriberId", null);
            return;
        }

        if (context == null) {
            callback.onFailure(null, "SDK not initialized");
            return;
        }

        if (prefs == null) {
            callback.onFailure(null, "SDK preferences not initialized");
            return;
        }

        try {
            // Check subscription status first (matches iOS implementation)
            getSubscriptionStatus(new PushEngageResponseCallback() {
                @Override
                public void onSuccess(Object responseObject) {
                    try {
                        boolean isSubscribed = (Boolean) responseObject;
                        String subscriberHash = prefs.getHash();

                        // Return subscriber hash only if user is subscribed and has a valid hash
                        if (isSubscribed && !TextUtils.isEmpty(subscriberHash)) {
                            callback.onSuccess(subscriberHash);
                        } else {
                            callback.onSuccess(null);
                        }
                    } catch (Exception e) {
                        callback.onFailure(null, "Error processing subscription status: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(Integer errorCode, String errorMessage) {
                    callback.onSuccess(null);
                }
            });

        } catch (Exception e) {
            callback.onFailure(null, "Failed to get subscriber ID: " + e.getMessage());
        }
    }

    /**
     * Manually unsubscribe the user from push notifications
     *
     * This method unsubscribes the user from receiving push notifications while
     * preserving their subscription record. The user can be re-subscribed later
     * using the subscribe() method.
     *
     * @param callback The callback to be invoked with the unsubscribe result
     *                 - onSuccess: Unsubscribe operation completed successfully
     *                 - onFailure: Unsubscribe operation failed
     *
     *                 Example usage:
     *
     *                 PushEngage.unsubscribe(new PushEngageResponseCallback() {
     *                 {@literal @}Override
     *                 public void onSuccess(Object result) {
     *                 Log.d("Subscription", "User unsubscribed successfully");
     *                 }
     *
     *                 {@literal @}Override
     *                 public void onFailure(Integer errorCode, String errorMessage)
     *                 {
     *                 Log.e("Subscription", "Failed to unsubscribe: " +
     *                 errorMessage);
     *                 }
     *                 });
     */
    public static void unsubscribe(PushEngageResponseCallback callback) {
        if (context == null) {
            if (callback != null) {
                callback.onFailure(null, "SDK not initialized");
            }
            return;
        }

        if (prefs == null) {
            if (callback != null) {
                callback.onFailure(null, "Preferences not available");
            }
            return;
        }

        // Check if user has a valid subscription to unsubscribe from
        String hash = prefs.getHash();
        if (TextUtils.isEmpty(hash)) {
            if (callback != null) {
                callback.onSuccess(true);
            }
            return;
        }

        try {
            // Set manually unsubscribed flag locally
            prefs.setIsManuallyUnsubscribed(true);

            // Update server with unsubscribed status
            UpdateSubscriberStatusRequest request = new UpdateSubscriberStatusRequest(
                    prefs.getSiteId(),
                    hash,
                    1L, // 1 = unsubscribed
                    prefs.getDeleteOnNotificationDisable());

            Call<NetworkResponse> call = RestClient.getBackendClient(context).updateSubscriberStatus(request);
            call.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(Call<NetworkResponse> call, Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        PELogger.debug("User unsubscribed successfully");
                        if (callback != null) {
                            callback.onSuccess(true);
                        }
                    } else {
                        PELogger.error("Unsubscribe API failed with response code: " + response.code(), null);
                        // Revert local changes on API failure
                        prefs.setIsManuallyUnsubscribed(false);
                        if (callback != null) {
                            callback.onFailure(response.code(), "Server error: " + response.code());
                        }
                    }
                }

                @Override
                public void onFailure(Call<NetworkResponse> call, Throwable t) {
                    PELogger.error("Unsubscribe API call failed", new Exception(t));
                    // Revert local changes on API failure
                    prefs.setIsManuallyUnsubscribed(false);
                    if (callback != null) {
                        callback.onFailure(null, "Network error: " + t.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            PELogger.error("Error during unsubscribe", e);
            // Revert local changes on error
            prefs.setIsManuallyUnsubscribed(false);
            if (callback != null) {
                callback.onFailure(null, "Failed to unsubscribe: " + e.getMessage());
            }
        }
    }

    /**
     * Manually subscribe the user to push notifications
     *
     * This method subscribes the user to push notifications. The implementation
     * matches iOS logic for cross-platform consistency. It checks permission status
     * and subscriber hash to determine the appropriate action.
     *
     * @param callback The callback to be invoked with the subscribe result
     *                 - onSuccess: Subscribe operation completed successfully
     *                 - onFailure: Subscribe operation failed
     *
     *                 Example usage:
     *
     *                 PushEngage.subscribe(new PushEngageResponseCallback() {
     *                 {@literal @}Override
     *                 public void onSuccess(Object result) {
     *                 Log.d("Subscription", "User subscribed successfully");
     *                 }
     *
     *                 {@literal @}Override
     *                 public void onFailure(Integer errorCode, String errorMessage)
     *                 {
     *                 Log.e("Subscription", "Failed to subscribe: " +
     *                 errorMessage);
     *                 }
     *                 });
     */
    public static void subscribe(ComponentActivity activity, PushEngageResponseCallback callback) {
        if (context == null) {
            if (callback != null) {
                callback.onFailure(null, "SDK not initialized");
            }
            return;
        }

        if (prefs == null) {
            if (callback != null) {
                callback.onFailure(null, "Preferences not available");
            }
            return;
        }

        try {
            String permissionStatus = getNotificationPermissionStatus();
            String subscriberHash = prefs.getHash();

            // If notification permission is granted AND we have subscriber data, call
            // update
            if ("granted".equals(permissionStatus) && !TextUtils.isEmpty(subscriberHash)) {
                PELogger.debug(
                        "Permission granted and subscriber data exists - calling updateSubscriberStatus(status: 0)");

                UpdateSubscriberStatusRequest request = new UpdateSubscriberStatusRequest(
                        prefs.getSiteId(),
                        subscriberHash,
                        0L, // 0 = subscribed
                        prefs.getDeleteOnNotificationDisable());

                Call<NetworkResponse> call = RestClient.getBackendClient(context).updateSubscriberStatus(request);
                call.enqueue(new Callback<NetworkResponse>() {
                    @Override
                    public void onResponse(Call<NetworkResponse> call, Response<NetworkResponse> response) {
                        if (response.isSuccessful()) {
                            // Clear flags after successful subscription
                            prefs.setIsManuallyUnsubscribed(false);
                            prefs.setIsSubscriberDeleted(false);
                            PELogger.debug("Manual subscription successful via update");
                            if (callback != null) {
                                callback.onSuccess(true);
                            }
                        } else if (response.code() == 404) {
                            // Handle 404 by retrying add subscriber process
                            PELogger.debug("Received 404, retrying add subscriber process");
                            prefs.setIsManuallyUnsubscribed(false);

                            try {
                                callAddSubscriberAPI();
                                if (callback != null) {
                                    callback.onSuccess(true);
                                }
                            } catch (Exception e) {
                                PELogger.error("Retrying to add subscriber failed", e);
                                if (callback != null) {
                                    callback.onFailure(null, "Failed to retry add subscriber: " + e.getMessage());
                                }
                            }
                        } else {
                            PELogger.error("Subscribe API failed with response code: " + response.code(), null);
                            if (callback != null) {
                                callback.onFailure(response.code(), "Server error: " + response.code());
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call<NetworkResponse> call, Throwable t) {
                        PELogger.error("Subscribe API call failed", new Exception(t));
                        if (callback != null) {
                            callback.onFailure(null, "Network error: " + t.getMessage());
                        }
                    }
                });
            } else {

                // If permission is not granted, we should guide user to request permission
                if (!"granted".equals(permissionStatus)) {
                    requestNotificationPermission(activity, new PushEngagePermissionCallback() {
                        @Override
                        public void onPermissionResult(boolean granted, Error error) {
                            if (granted) {
                                PELogger.debug("Notification permission granted, calling add subscriber.");
                                try {
                                    callAddSubscriberAPI();
                                    if (callback != null) {
                                        callback.onSuccess(true);
                                    }
                                } catch (Exception e) {
                                    PELogger.error("Add subscriber failed after permission grant", e);
                                    if (callback != null) {
                                        callback.onFailure(null, "Failed to add subscriber: " + e.getMessage());
                                    }
                                }
                            } else {
                                PELogger.debug("Notification permission denied.");
                                if (callback != null) {
                                    callback.onFailure(null, "Notification permission denied.");
                                }
                            }
                        }
                    });
                    return;
                }

                try {
                    callAddSubscriberAPI();
                    if (callback != null) {
                        callback.onSuccess(true);
                    }
                } catch (Exception e) {
                    PELogger.error("Add subscriber failed", e);
                    if (callback != null) {
                        callback.onFailure(null, "Failed to add subscriber: " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            PELogger.error("Error during subscribe", e);
            if (callback != null) {
                callback.onFailure(null, "Failed to subscribe: " + e.getMessage());
            }
        }
    }

    /**
     * Enables or disables logging for the PushEngage library based on the specified
     * boolean value.
     *
     * @param shouldEnable A boolean value indicating whether logging should be
     *                     enabled or
     *                     disabled.
     *
     *                     Example usage:
     *                     PushEngage.enableLogging(true);
     *
     */
    public static void enableLogging(Boolean shouldEnable) {
        PELogger.enableLogging(shouldEnable);
    }

    /**
     * Allows to enable or disable trigger campaigns for a subscriber
     * with the specified callback and trigger status.
     *
     * @param status   The trigger status type indicating the status of the trigger
     *                 campaign.
     *                 See {@link TriggerStatusType} for possible values.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     *
     * 
     *                 Example usage:
     *                 PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled,
     *                 object : PushEngageResponseCallback {
     *                 override fun onSuccess(responseObject: Any?) {
     *                 Toast.makeText(this@TriggerCampaignActivity, "Trigger Enabled
     *                 successfully", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage:
     *                 String?) {
     *                 Toast.makeText(this@TriggerCampaignActivity, "Trigger Enabled
     *                 failed", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 })
     */
    public static void automatedNotification(TriggerStatusType status, PushEngageResponseCallback callback) {
        peManager.automatedNotification(status, callback);
    }

    /**
     * Allows to enable or disable trigger campaigns for a subscriber
     * with a trigger status.
     *
     * @param status The trigger status type indicating the status of the trigger
     *               campaign.
     *               See {@link TriggerStatusType} for possible values.
     *
     *               Example usage:
     *               PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled)
     */
    public static void automatedNotification(TriggerStatusType status) {
        automatedNotification(status, null);
    }

    /**
     * Sends a trigger event for a specific campaign with the provided callback for
     * handling the response.
     *
     * @param trigger  The {@link TriggerCampaign} object representing the campaign
     *                 event to be triggered.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     *
     *                 Example usage:
     *                 val triggerCampaign = TriggerCampaign(
     *                 "name_of_campaign",
     *                 "event_name_of_trigger")
     *
     *                 PushEngage.sendTriggerEvent(triggerCampaign, object :
     *                 PushEngageResponseCallback {
     *                 override fun onSuccess(responseObject: Any?) {
     *                 Toast.makeText(this@TriggerEntryActivity,"Send Trigger Alert
     *                 Successfully", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage:
     *                 String?) {
     *                 Toast.makeText(this@TriggerEntryActivity,errorMessage.toString(),
     *                 Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 })
     */
    public static void sendTriggerEvent(TriggerCampaign trigger, PushEngageResponseCallback callback) {
        peManager.sendTriggerEvent(trigger, callback);
    }

    /**
     * Sends a trigger event for a specific campaign.
     *
     * @param trigger The {@link TriggerCampaign} object representing the campaign
     *                event to be triggered.
     *
     *                Example usage:
     *                val triggerCampaign = TriggerCampaign(
     *                "name_of_campaign",
     *                "event_name_of_trigger")
     *
     *                PushEngage.sendTriggerEvent(triggerCampaign)
     */
    public static void sendTriggerEvent(TriggerCampaign trigger) {
        sendTriggerEvent(trigger, null);
    }

    /**
     * Sends a goal event with the provided callback for handling the response.
     *
     * @param goal     The {@link Goal} object representing the goal to be tracked.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     *
     *                 Example usage:
     *                 val goal = Goal("revenue", 1, 10)
     *                 PushEngage.sendGoal(goal, object: PushEngageResponseCallback
     *                 {
     *                 override fun onSuccess(responseObject: Any?) {
     *                 Toast.makeText(this@GoalActivity, "Success",
     *                 Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage:
     *                 String?) {
     *                 Toast.makeText(this@GoalActivity, "Failure",
     *                 Toast.LENGTH_LONG).show()
     *                 }
     *                 })
     */
    public static void sendGoal(Goal goal, PushEngageResponseCallback callback) {
        peManager.sendGoal(goal, callback);
    }

    /**
     * Sends a goal event.
     *
     * @param goal The {@link Goal} object representing the completed goal to be
     *             tracked.
     *
     *             Example usage:
     *             val goal = Goal("revenue", 1, 10)
     *             PushEngage.sendGoal(goal)
     */
    public static void sendGoal(Goal goal) {
        sendGoal(goal, null);
    }

    /**
     * Adds an alert to be triggered with the provided callback for handling the
     * response.
     *
     * @param alert    The {@link TriggerAlert} object representing the alert to be
     *                 added.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     *
     *                 Example usage:
     *                 val triggerAlert = TriggerAlert(
     *                 TriggerAlertType.inventory,
     *                 "product_id",
     *                 "link",
     *                 20.0)
     *
     *                 PushEngage.addAlert(triggerAlert, object :
     *                 PushEngageResponseCallback {
     *                 override fun onSuccess(responseObject: Any?) {
     *                 Toast.makeText(this@AddAlertActivity, "Add Alert
     *                 Successfully", Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 override fun onFailure(errorCode: Int?, errorMessage:
     *                 String?) {
     *                 Toast.makeText(this@AddAlertActivity, errorMessage,
     *                 Toast.LENGTH_LONG).show()
     *                 }
     *
     *                 })
     */
    public static void addAlert(TriggerAlert alert, PushEngageResponseCallback callback) {
        peManager.addAlert(alert, callback);
    }

    /**
     * Adds an alert to be triggered.
     *
     * @param alert The {@link TriggerAlert} object representing the alert to be
     *              added.
     *
     *              Example usage:
     *              val triggerAlert = TriggerAlert(
     *              TriggerAlertType.inventory,
     *              "product_id",
     *              "link",
     *              20.0)
     *
     *              PushEngage.addAlert(triggerAlert)
     */
    public static void addAlert(TriggerAlert alert) {
        addAlert(alert, null);
    }

    /**
     * Initiates an API call to synchronize the client device with data from the
     * server.
     * If successful, it triggers the subscription process or updating of subscriber
     * data based
     * on the site status and notification preferences.
     */
    private static void callAndroidSync() {
        PELogger.debug("Sync for SiteKey = " + prefs.getSiteKey() + " called");
        if (PEUtilities.checkNetworkConnection(context)) {
            Call<AndroidSyncResponse> addRecordsResponseCall = RestClient.getBackendCdnClient(context)
                    .androidSync(prefs.getSiteKey());
            addRecordsResponseCall.enqueue(new Callback<AndroidSyncResponse>() {
                @Override
                public void onResponse(@NonNull Call<AndroidSyncResponse> call,
                        @NonNull Response<AndroidSyncResponse> response) {
                    if (response.isSuccessful()) {
                        AndroidSyncResponse androidSyncResponse = response.body();
                        if (androidSyncResponse.getData().getSiteStatus().equalsIgnoreCase(PEConstants.ACTIVE)) {
                            /*
                             * Updates preferences with the received server data.
                             */
                            prefs.setBackendUrl(androidSyncResponse.getData().getApi().getBackend());
                            prefs.setBackendCdnUrl(androidSyncResponse.getData().getApi().getBackendCdn());
                            prefs.setAnalyticsUrl(androidSyncResponse.getData().getApi().getAnalytics());
                            prefs.setTriggerUrl(androidSyncResponse.getData().getApi().getTrigger());
                            prefs.setOptinUrl(androidSyncResponse.getData().getApi().getOptin());
                            prefs.setLoggerUrl(androidSyncResponse.getData().getApi().getLog());
                            prefs.setSiteId(androidSyncResponse.getData().getSiteId());
                            prefs.setProjectId(androidSyncResponse.getData().getFirebaseSenderId());
                            prefs.setDeleteOnNotificationDisable(
                                    androidSyncResponse.getData().getDeleteOnNotificationDisable());
                            prefs.setSiteStatus(androidSyncResponse.getData().getSiteStatus());
                            prefs.setGeoFetch(androidSyncResponse.getData().getGeoLocationEnabled());
                            prefs.setEu(androidSyncResponse.getData().getIsEu());

                            /*
                             * Checks if notifications are enabled and processes subscriber data
                             * accordingly.
                             */
                            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat
                                    .from(context);
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
                             * Handles server response indicating non-active site status.
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
                            }, DELAY);// 3 minutes delay
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
                        }, DELAY);// 3 minutes delay
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
                }, DELAY);// 3 minutes delay
            }
        }
    }

    private static Boolean isTablet() {
        return context.getResources().getBoolean(R.bool.is_tablet);
    }

    /**
     * Initiates an API call to add the client as a subscriber, sending necessary
     * device and
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
        AddSubscriberRequest.Subscription subscription = addSubscriberRequest.new Subscription(prefs.getDeviceToken(),
                String.valueOf(prefs.getProjectId()));
        addSubscriberRequest = new AddSubscriberRequest(prefs.getSiteId(), subscription, PEConstants.ANDROID, device,
                deviceVersion, deviceModel, deviceManufacturer, timeZone, language, deviceName, screenSize, packageName,
                prefs.isNotificationDisabled());
        if (PEUtilities.checkNetworkConnection(context)) {
            Call<AddSubscriberResponse> addSubscriberResponseCall = RestClient.getBackendClient(context).addSubscriber(
                    addSubscriberRequest, getSdkVersion(), String.valueOf(prefs.getEu()),
                    String.valueOf(prefs.isGeoFetch()));
            addSubscriberResponseCall.enqueue(new Callback<AddSubscriberResponse>() {
                @Override
                public void onResponse(@NonNull Call<AddSubscriberResponse> call,
                        @NonNull Response<AddSubscriberResponse> response) {
                    if (response.isSuccessful()) {
                        AddSubscriberResponse addSubscriberResponse = response.body();
                        prefs.setHash(addSubscriberResponse.getData().getSubscriberHash());
                        prefs.setIsSubscriberDeleted(false);
                        prefs.setIsManuallyUnsubscribed(false);
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
                            }, DELAY);// 3 minutes delay
                        } else {
                            String jsonStr = gson.toJson(response.body());
                            ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                            ErrorLogRequest.Data data = errorLogRequest.new Data("callAddSubscriberAPI",
                                    prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
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
                        }, DELAY);// 3 minutes delay
                    } else {
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        ErrorLogRequest.Data data = errorLogRequest.new Data("callAddSubscriberAPI", prefs.getHash(),
                                PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
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
                }, DELAY);// 3 minutes delay
            }
        }
    }

    /**
     * Retrieves the last notification payload data received by the client app.
     *
     * @return A {@code String} representing the last notification payload data
     *         stored in preferences.
     */
    private static String getLastNotificationPayload() {
        return prefs.getPayload();
    }

    /**
     * Enables a weekly synchronization task for subscriber data. This method
     * schedules a periodic
     * work request to run the {@link WeeklySyncDataWorker} at regular intervals.
     * The worker performs data synchronization tasks related to subscribers on a
     * weekly basis.
     */
    private static void enableWeeklyDataSync() {
        /*
         * Network constraint to ensure the device is connected to the network for
         * synchronization.
         */
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        /*
         * Configures a periodic work request to run the WeeklySyncDataWorker every 7
         * days with a 1-hour
         * flex interval. The worker performs synchronization tasks related to
         * subscriber data.
         */
        PeriodicWorkRequest periodicSyncDataWork = new PeriodicWorkRequest.Builder(WeeklySyncDataWorker.class, 7,
                TimeUnit.DAYS, 1, // flex interval - worker will run somewhen within this period of time, but at
                                  // the end of repeating interval
                TimeUnit.HOURS)
                .addTag(PEConstants.WEEKLY_SYNC_DATA)
                .setConstraints(constraints)
                // setting a backoff in case the work needs to retry
                .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PEConstants.WEEKLY_SYNC_DATA,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSyncDataWork);
    }

    /**
     * Enables a daily synchronization task for checking notification permissions.
     * This method schedules a periodic work request to run the
     * {@link DailySyncDataWorker}
     * at regular intervals. The worker checks notification permissions and performs
     * related tasks daily.
     */
    private static void enableDailyDataSync() {
        /*
         * Network constraint to ensure the device is connected to the network for
         * synchronization.
         */
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        /*
         * Configures a periodic work request to run the DailySyncDataWorker every 30
         * minutes with a
         * 5-minute flex interval. The worker checks notification permissions and
         * performs related tasks daily.
         */
        PeriodicWorkRequest periodicSyncDataWork = new PeriodicWorkRequest.Builder(DailySyncDataWorker.class, 30,
                TimeUnit.MINUTES, 5,
                TimeUnit.MINUTES)
                .addTag(PEConstants.DAILY_SYNC_DATA)
                .setConstraints(constraints)
                // setting a backoff in case the work needs to retry
                .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        /*
         * Enqueues the unique periodic work request with a specific tag and existing
         * periodic work policy.
         */
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PEConstants.DAILY_SYNC_DATA,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSyncDataWork);

    }

    /**
     * Initiates an API call to retrieve subscriber details based on the provided
     * list of values.
     *
     * @param values   A {@code List<String>} containing subscriber-related values
     *                 for the API request.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks along with the subscriber data
     *                 if successful.
     */
    public static void getSubscriberDetails(List<String> values, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            // Convert List<String> to comma-separated string
            String fieldsString = String.join(",", values);
            Call<NetworkResponse> subscriberDetailsResponseCall = RestClient.getBackendClient(context)
                    .subscriberDetails(prefs.getHash(), fieldsString);
            subscriberDetailsResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse genericResponse = response.body();
                        if (callback != null && genericResponse != null)
                            callback.onSuccess(genericResponse.getData());
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * Initiates an API call to retrieve subscriber attributes associated with the
     * subscriber hash.
     *
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks along with the subscriber
     *                 attributes if successful.
     */
    public static void getSubscriberAttributes(PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> getSubscriberAttributesResponseCall = RestClient.getBackendClient(context)
                    .getSubscriberAttributes(prefs.getHash());
            getSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse genericResponse = response.body();
                        if (callback != null && genericResponse != null)
                            callback.onSuccess(genericResponse.getData());
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * Initiates an API call to delete specified subscriber attributes associated
     * with the subscriber.
     * This method allows deletion of multiple attributes at once.
     *
     * @param values A {@code List<String>} containing attribute names to be
     *               deleted.
     */
    public static void deleteSubscriberAttributes(List<String> values) {
        deleteSubscriberAttributes(values, null);
    }

    /**
     * Initiates an API call to delete specified subscriber attributes associated
     * with the subscriber.
     * This method allows deletion of multiple attributes at once and provides a
     * callback for handling
     * the API response asynchronously.
     *
     * @param values   A {@code List<String>} containing attribute names to be
     *                 deleted.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     */
    public static void deleteSubscriberAttributes(List<String> values, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> deleteSubscriberAttributesResponseCall = RestClient.getBackendClient(context)
                    .deleteSubscriberAttributes(prefs.getHash(), values);
            deleteSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * Updates attributes of a subscriber. If an attribute with the specified key
     * already exists, the existing value will be replaced.
     * Does not provide a callback for API response handling.
     *
     * @param obj A {@link JSONObject} containing the subscriber attributes to be
     *            added.
     */
    public static void addSubscriberAttributes(JSONObject obj) {
        addSubscriberAttributes(obj, null);
    }

    /**
     * Updates attributes of a subscriber. If an attribute with the specified key
     * already exists, the existing value will be replaced.
     * Provides a callback for handling the API response asynchronously.
     *
     * @param obj      A {@link JSONObject} containing the subscriber attributes to
     *                 be added.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     */
    public static void addSubscriberAttributes(JSONObject obj, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<NetworkResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(context)
                    .addAttributes(prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * Sets attributes of a subscriber replacing any previously associated
     * attributes.
     * This method allows updating multiple attributes at once and does not provide
     * a callback for API response handling.
     *
     * @param obj A {@link JSONObject} containing the updated subscriber attributes.
     */
    public static void setSubscriberAttributes(JSONObject obj) {
        setSubscriberAttributes(obj, null);
    }

    /**
     * Sets attributes of a subscriber replacing any previously associated
     * attributes.
     * This method allows updating multiple attributes at once and provides a
     * callback for handling
     * the API response asynchronously.
     *
     * @param obj      A {@link JSONObject} containing the updated subscriber
     *                 attributes.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     */
    public static void setSubscriberAttributes(JSONObject obj, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<NetworkResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(context)
                    .setAttributes(prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * This method allows adding a profile ID and does not provide a callback for
     * API response handling.
     *
     * @param profileId A {@link String} representing the profile ID to be
     *                  associated with the subscriber.
     */
    public static void addProfileId(String profileId) {
        addProfileId(profileId, null);
    }

    /**
     * Initiates an API call to associate a profile ID with the subscriber.
     * This method allows adding a profile ID and provides a callback for handling
     * the API response asynchronously.
     *
     * @param profileId A {@link String} representing the profile ID to be
     *                  associated with the subscriber.
     * @param callback  A callback interface to handle API response asynchronously.
     *                  Implement the {@link PushEngageResponseCallback} interface
     *                  to receive
     *                  success or failure callbacks.
     */
    public static void addProfileId(String profileId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddProfileIdRequest addProfileIdRequest = new AddProfileIdRequest(
                    prefs.getHash(), profileId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> addProfileIdResponseCall = RestClient.getBackendClient(context)
                    .addProfileId(addProfileIdRequest);
            addProfileIdResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * Initiates an API call to add segments with a callback for handling the
     * response.
     * This method allows the addition of subscriber to a segment and provides a
     * callback
     * mechanism to handle the success or failure response from the server.
     *
     * @param segmentId A list of segment IDs to be added.
     * @param callback  A callback interface to handle API response asynchronously.
     *                  Implement the {@link PushEngageResponseCallback} interface
     *                  to receive
     *                  success or failure callbacks.
     */
    public static void addSegment(List<String> segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddSegmentRequest addSegmentRequest = new AddSegmentRequest(prefs.getHash(), segmentId, prefs.getSiteId(),
                    PEConstants.ANDROID);
            Call<NetworkResponse> addSegmentResponseCall = RestClient.getBackendClient(context)
                    .addSegments(addSegmentRequest);
            addSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * This method allows the removal of specified segments for a subscriber. It
     * does not provide a
     * callback for handling the success or failure response from the server.
     *
     * @param segmentId A list of segment IDs to be removed. Must not be null.
     */
    public static void removeSegment(List<String> segmentId) {
        removeSegment(segmentId, null);
    }

    /**
     * Initiates an API call to remove segments with a provided callback for
     * handling the response.
     * This method allows the removal of specified segments for a subscriber. It
     * provides a callback
     * mechanism to handle the success or failure response from the server.
     *
     * @param segmentId A list of segment IDs to be removed.
     * @param callback  A callback interface to handle API response asynchronously.
     *                  Implement the {@link PushEngageResponseCallback} interface
     *                  to receive
     *                  success or failure callbacks.
     */
    public static void removeSegment(List<String> segmentId, PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            RemoveSegmentRequest removeSegmentRequest = new RemoveSegmentRequest(
                    prefs.getHash(), segmentId, prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> removeSegmentResponseCall = RestClient.getBackendClient(context)
                    .removeSegments(removeSegmentRequest);
            removeSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
     * Initiates an API call to add dynamic segments with a provided list of
     * segments and a callback for handling the response.
     * This method allows the addition of dynamic segments to a subscriber's
     * profile. Dynamic segments are defined by a list
     * of segment objects containing specific criteria.
     *
     * @param segments A list of segment objects representing dynamic segments to be
     *                 added. Must not be null.
     */
    public static void addDynamicSegment(List<AddDynamicSegmentRequest.Segment> segments) {
        addDynamicSegment(segments, null);
    }

    /**
     * Initiates an API call to add dynamic segments with a provided list of
     * segments and a callback for handling the response.
     * This method allows the addition of dynamic segments to a subscriber's
     * profile. Dynamic segments are defined by a list
     * of segment objects containing specific criteria. It provides a callback
     * mechanism to handle the success or failure
     * response from the server.
     *
     * @param segments A list of segment objects representing dynamic segments to be
     *                 added. Must not be null.
     * @param callback A callback interface to handle API response asynchronously.
     *                 Implement the {@link PushEngageResponseCallback} interface to
     *                 receive
     *                 success or failure callbacks.
     */
    public static void addDynamicSegment(List<AddDynamicSegmentRequest.Segment> segments,
            PushEngageResponseCallback callback) {
        String validationResult = PEUtilities.apiPreValidate(context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddDynamicSegmentRequest addDynamicSegmentRequest = new AddDynamicSegmentRequest(prefs.getHash(),
                    prefs.getSiteId(), PEConstants.ANDROID, segments);
            Call<NetworkResponse> addDynamicSegmentResponseCall = RestClient.getBackendClient(context)
                    .addDynamicSegments(addDynamicSegmentRequest);
            addDynamicSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
            SegmentHashArrayRequest segmentHashArrayRequest = new SegmentHashArrayRequest(prefs.getHash(),
                    prefs.getSiteId(), segmentId);
            Call<NetworkResponse> segmentHashArrayResponseCall = RestClient.getBackendClient(context)
                    .getSegmentHashArray(segmentHashArrayRequest);
            segmentHashArrayResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
            Call<NetworkResponse> checkSubscriberHashResponseCall = RestClient.getBackendClient(context)
                    .checkSubscriberHash(prefs.getHash());
            checkSubscriberHashResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                JSONObject jsonObj = new JSONObject(readText(response.errorBody().charStream()));
                                if (callback != null)
                                    callback.onFailure(jsonObj.getInt("error_code"),
                                            jsonObj.getString("error_message"));
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
