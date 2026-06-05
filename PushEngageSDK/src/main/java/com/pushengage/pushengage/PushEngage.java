package com.pushengage.pushengage;

import static kotlin.io.TextStreamsKt.readText;

import android.annotation.SuppressLint;
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
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
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
import com.google.firebase.FirebaseOptions;
import com.google.firebase.installations.FirebaseInstallationsException;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pushengage.pushengage.Callbacks.FcmConfigErrorListener;
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback;
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback;
import com.pushengage.pushengage.internal.subscriber.PESubscriberFieldsHandler;
import com.pushengage.pushengage.DataWorker.DailySyncDataWorker;
import com.pushengage.pushengage.DataWorker.WeeklySyncDataWorker;
import com.pushengage.pushengage.permissionhandling.PEPermissionFragment;

import androidx.fragment.app.FragmentActivity;
import com.pushengage.pushengage.Receiver.NetworkChangeReceiver;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.helper.PEPlatformDetector;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest;
import com.pushengage.pushengage.model.request.AddProfileIdRequest;
import com.pushengage.pushengage.model.request.AddSegmentRequest;
import com.pushengage.pushengage.model.request.AddSubscriberRequest;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.request.Goal;
import com.pushengage.pushengage.model.request.TrackEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.io.TextStreamsKt;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import androidx.appcompat.app.AppCompatActivity;

public class PushEngage {

    // Singleton-owned state. Public static methods read these via the `instance` field.
    // No static state holds the SDK's working data — that lives on the singleton
    // so re-init or test-time reflection can't stomp it midway through an in-flight call.
    private Context context;
    private PEPrefs prefs;
    private PEManagerType peManager;
    private int addSubscribeRetryCount = 0;
    private int siteSyncRetryCount = 0;
    private final AtomicBoolean isFirebaseApiCallInProgress = new AtomicBoolean(false);
    private BroadcastReceiver networkChangeReceiver;

    // FCM config-error listener is class-level rather than instance-level on purpose:
    // hosts can register it before Builder.build(), so it cannot depend on a singleton existing.
    // volatile so that an update from the host-app thread is visible to the OkHttp
    // dispatcher / FIS callback threads on the next read.
    private static volatile FcmConfigErrorListener fcmConfigErrorListener;

    // Constants — these legitimately have no per-instance meaning.
    private static final String TAG = "PushEngage";
    private static final int RETRY_COUNT = 3;
    private static final Gson gson = new Gson();
    private static final int DELAY = 180000;

    // Singleton machinery.
    private static final Object INIT_LOCK = new Object();
    // The singleton transitively references a Context, which trips Lint's StaticFieldLeak
    // check. Safe here: Builder.build()'s constructor captures applicationContext (see the
    // private PushEngage(Context, String) ctor), which is process-scoped and outlives every
    // Activity. No Activity can be leaked through this field.
    @SuppressLint("StaticFieldLeak")
    private static volatile PushEngage instance;

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
     * @param ctx The Android application context in which the library will
     *                operate.
     * @param siteKey The unique identifier for the PushEngage site.
     */
    private PushEngage(Context ctx, String siteKey) {
        // Always capture applicationContext so callers passing an Activity don't leak it.
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.context = appCtx;
        this.prefs = new PEPrefs(appCtx);
        if (isAppIdValid(siteKey)) {
            this.prefs.setSiteKey(siteKey);
        } else {
            // Build was called without a valid App ID. Preserve any previously stored
            // value so an unrelated re-init path can't blow away a working configuration.
            // The sync/subscriber call sites still enforce their own guard for the
            // fresh-install case where prefs hold nothing.
            PELogger.error(
                    "PushEngage.Builder().setAppId(\"YOUR_APP_ID\") was not called (or received null/blank). "
                            + "Existing App ID in preferences was preserved. "
                            + "Sync and subscriber requests will be skipped until a valid App ID is configured.",
                    null);
        }
        registerNetworkReceiver();
        this.peManager = new PEManager(appCtx, this.prefs);
    }

    /**
     * Package-private constructor used by tests to install a singleton with pre-built
     * dependencies without firing the real-init side effects (receiver registration,
     * PEPrefs construction, auto-subscribe).
     */
    PushEngage(Context appContext, PEPrefs prefs, PEManagerType peManager) {
        this.context = appContext;
        this.prefs = prefs;
        this.peManager = peManager;
    }

    /**
     * Lives outside the constructor so initialization side effects don't fire while
     * shared state is still being assigned. Called after the singleton is installed.
     */
    private static void maybeAutoSubscribe() {
        PushEngage inst = instance;
        if (inst == null || inst.prefs == null) {
            return;
        }
        if (TextUtils.isEmpty(inst.prefs.getHash()) &&
                "granted".equals(getNotificationPermissionStatus()) &&
                !inst.prefs.isManuallyUnsubscribed()) {
            subscribe();
        }
    }

    /**
     * Standard HTTP-failure dispatcher used by the response-callback APIs.
     * <p>
     * Reads the Retrofit error body once, tries to extract the server's
     * {@code error_code} / {@code error_message} JSON, and falls back to the
     * raw body text so callers see the actual server response rather than the
     * localized "Server Error" placeholder when the payload isn't the expected
     * shape. Only used in HTTP-error paths; the network-failure path keeps its
     * existing Throwable-message behavior.
     */
    private static void dispatchHttpFailure(@NonNull Response<?> response,
                                            @Nullable PushEngageResponseCallback callback,
                                            @NonNull Context context) {
        if (callback == null) return;
        String rawBody = "";
        if (response.errorBody() != null) {
            try {
                rawBody = readText(response.errorBody().charStream());
            } catch (Exception ignored) {
            }
        }
        if (!TextUtils.isEmpty(rawBody)) {
            try {
                JSONObject jsonObj = new JSONObject(rawBody);
                int code = jsonObj.has("error_code")
                        ? jsonObj.optInt("error_code", response.code())
                        : response.code();
                String message = jsonObj.optString("error_message", "");
                if (TextUtils.isEmpty(message)) message = rawBody;
                callback.onFailure(code, message);
                return;
            } catch (Exception ignored) {
                // Not JSON — fall through and surface the raw body.
            }
            callback.onFailure(response.code(), rawBody);
            return;
        }
        callback.onFailure(response.code(), context.getString(R.string.server_error));
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

        @Nullable
        public PushEngage build() {
            synchronized (INIT_LOCK) {
                if (instance == null) {
                    if (context == null) {
                        PELogger.error(
                                "PushEngage.Builder().addContext(...) was not called. build() is a no-op.",
                                null);
                        return null;
                    }
                    instance = new PushEngage(context, siteKey);
                } else {
                    // Re-init: refresh the App ID (when valid) and reset retry counters to
                    // preserve pre-refactor behavior where a fresh build() restarted retries.
                    // Receiver/prefs/peManager are preserved so pending callbacks aren't orphaned.
                    if (isAppIdValid(siteKey) && instance.prefs != null) {
                        String previousSiteKey = instance.prefs.getSiteKey();
                        if (previousSiteKey != null && !previousSiteKey.isEmpty()
                                && !previousSiteKey.equals(siteKey)) {
                            // App ID switched between builds — wipe cached subscriber/site
                            // state so an unsubscribe()/getSubscriberId() fired before the
                            // next sync can't act on the previous site's identifiers.
                            PELogger.error(
                                    "App ID changed from '" + previousSiteKey + "' to '" + siteKey
                                            + "' — cached subscriber state cleared. The next sync will re-register this device.",
                                    null);
                            instance.prefs.clearSiteSpecificData();
                        }
                        instance.prefs.setSiteKey(siteKey);
                    }
                    instance.addSubscribeRetryCount = 0;
                    instance.siteSyncRetryCount = 0;
                }
                // Re-detect on every build() so wrapper additions/removals across
                // host-app versions propagate without requiring wrapper code changes.
                // setPlatform() does not wipe a previously-set wrapperVersion — that
                // value is owned by the wrapper plugin's own init call.
                if (instance != null && instance.prefs != null) {
                    instance.prefs.setPlatform(PEPlatformDetector.INSTANCE.detect());
                }
            }
            // Run subscribe-on-init outside the lock so FirebaseApp.initializeApp()'s
            // classpath scan and file I/O don't extend the window where INIT_LOCK
            // blocks concurrent build() calls. The `instance` field is volatile, so
            // its publication from the synchronized block is visible here.
            maybeAutoSubscribe();
            return instance;
        }

    }

    /**
     * Records the wrapper SDK's own version so it can be reported alongside the
     * native SDK version in the User-Agent. Intended for wrapper plugins (Flutter,
     * React Native, etc.) to call from their Android-side init code — not for host
     * app developers. Empty/null clears the stored value.
     *
     * @param wrapperVersion the wrapper plugin's version string (e.g. "2.3.0")
     */
    public static void setWrapperVersion(String wrapperVersion) {
        PushEngage inst = requireInstance("setWrapperVersion");
        if (inst == null || inst.prefs == null) {
            return;
        }
        inst.prefs.setWrapperVersion(wrapperVersion);
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
        if (networkChangeReceiver != null) {
            // Already registered by a prior build(); don't pile up duplicates.
            return;
        }
        networkChangeReceiver = new NetworkChangeReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkChangeReceiver, filter);
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
     * Registers a listener invoked whenever the SDK detects an FCM configuration
     * problem — either a mismatch between the host app's google-services.json and
     * the configuration on the PushEngage dashboard, or an internally invalid
     * google-services.json reported by Firebase itself.
     * <p>
     * The listener fires with one of the {@link PEErrorCodes} constants:
     * {@code FCM_SENDER_ID_MISMATCH}, {@code FCM_PROJECT_ID_MISMATCH}, or
     * {@code FCM_LOCAL_CONFIG_INVALID}, plus a human-readable message detailing
     * the mismatch. Mismatches detected at sync time also cause the SDK to skip
     * the subscriber-add call, so no permanently-undeliverable subscriber is
     * created on the server.
     * <p>
     * Pass {@code null} to clear a previously-registered listener.
     *
     * @param listener the listener to invoke on config errors, or {@code null}
     */
    public static void setFcmConfigErrorListener(FcmConfigErrorListener listener) {
        fcmConfigErrorListener = listener;
    }

    /**
     * Returns the live SDK instance, or {@code null} after notifying the caller
     * via {@code callback.onFailure(null, "SDK not initialized")}. Call sites
     * must early-return when this returns {@code null}. Production callers that
     * additionally need {@code context} can rely on it being non-null whenever
     * {@code instance} is set via the public Builder; only test-seam construction
     * can produce a stub with null context.
     */
    private static PushEngage requireInstance(PushEngageResponseCallback callback) {
        PushEngage inst = instance;
        if (inst == null) {
            if (callback != null) {
                callback.onFailure(null, "SDK not initialized");
            }
            return null;
        }
        return inst;
    }

    /**
     * Returns the live SDK instance, or {@code null} after logging. Use from
     * fire-and-forget statics, getters, and internal callbacks that have no
     * caller-supplied error sink.
     */
    private static PushEngage requireInstance(String methodName) {
        PushEngage inst = instance;
        if (inst == null) {
            PELogger.error(methodName + " called before PushEngage.Builder().build()", null);
            return null;
        }
        return inst;
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
        PushEngage inst = requireInstance("setSmallIconResource");
        if (inst == null) return;
        inst.prefs.setSmallIconResource(resourceName);
    }

    /**
     * Sets the badge count associated with the application.
     * <p>
     * Android does not expose a system API for a numeric launcher-icon badge — only
     * a dot indicator on supported launchers, plus a count surfaced in the long-press
     * shortcut menu. The behavior of this method on Android is:
     * <ul>
     *     <li>{@code count == 0}: clears all active notifications via
     *         {@link NotificationManagerCompat#cancelAll()}, which removes the
     *         launcher dot.</li>
     *     <li>{@code count > 0}: stored in preferences and applied via
     *         {@code NotificationCompat.Builder.setNumber(count)} to notifications
     *         the SDK builds afterwards; the value is shown in the long-press menu
     *         and does not appear on the launcher icon itself.</li>
     * </ul>
     * Negative values are coerced to 0.
     *
     * @param count The badge count to set. Use 0 to clear.
     */
    public static void setBadgeCount(int count) {
        PushEngage inst = instance;
        if (inst == null) {
            PELogger.error("setBadgeCount called before PushEngage.Builder().build()", null);
            return;
        }
        int safeCount = Math.max(count, 0);
        inst.prefs.setBadgeCount(safeCount);
        if (safeCount == 0) {
            NotificationManagerCompat.from(inst.context).cancelAll();
        }
    }

    /**
     * Retrieves the device token associated with the client app.
     *
     * @return A {@code String} representing the device token obtained from the
     *         preferences manager.
     */
    private static String getDeviceToken() {
        PushEngage inst = requireInstance("getDeviceToken");
        if (inst == null) return "";
        return inst.prefs.getDeviceToken();
    }

    /**
     * Retrieves the device hash generated based on the device token associated with
     * the client app.
     *
     * @return A String representing the device token associated with the client
     *         app.
     */
    public static String getDeviceTokenHash() {
        PushEngage inst = requireInstance("getDeviceTokenHash");
        if (inst == null) return "";
        return inst.prefs.getHash();
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
        PushEngage inst = instance;
        if (inst == null || inst.context == null) {
            // SDK not initialized — callers like PEPermissionHelperActivity may invoke
            // subscribe() after a permission grant even if Builder.build() hasn't run yet.
            return;
        }
        FirebaseApp.initializeApp(inst.context);
        // Init-time advisory check: re-validate cached server values against the
        // current google-services.json. Never blocks subscribe; sync-time check is
        // authoritative. Catches the swap-google-services.json-between-sessions case.
        if (inst.prefs != null) {
            runConfigValidation(inst.prefs.getProjectId(), inst.prefs.getFirebaseProjectId());
        }
        if (!inst.isFirebaseApiCallInProgress.compareAndSet(false, true)) {
            return;
        }
        // FirebaseMessaging.getInstance() throws IllegalStateException when the host
        // hasn't configured google-services.json. Swallow it here so a missing-Firebase
        // config doesn't crash Builder.build()'s auto-subscribe path — the config
        // validator above has already logged the underlying cause for the host to fix.
        try {
            FirebaseMessaging.getInstance().setAutoInitEnabled(true);
            FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull Task<String> task) {
                        inst.isFirebaseApiCallInProgress.set(false);
                        if (!task.isSuccessful()) {
                            handleFirebaseTokenFailure(task.getException());
                            return;
                        } else {
                            // Get new FCM registration token
                            String token = task.getResult();
                            Log.d(TAG, token);
                            if (!inst.prefs.getDeviceToken().equals(token) || inst.prefs.getSiteId() == 0
                                    || inst.prefs.getHash().isEmpty()) {
                                inst.prefs.setDeviceToken(token);
                                callAndroidSync();
                            }
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        inst.isFirebaseApiCallInProgress.set(false);
                        handleFirebaseTokenFailure(e);
                    }
                });
        } catch (IllegalStateException e) {
            inst.isFirebaseApiCallInProgress.set(false);
            PELogger.error(
                    "FirebaseMessaging unavailable — google-services.json missing or invalid. Subscribe aborted.",
                    e);
        }
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
     * This method returns the permission status synchronously as a string by
     * querying {@link NotificationManagerCompat#areNotificationsEnabled()} at call
     * time (no cached value).
     *
     * <p>
     * <b>Two-state model on Android:</b> Unlike iOS, Android does not expose a
     * system-level "not yet requested" authorization state — only "notifications
     * enabled" or not. This method therefore returns only {@code "granted"} or
     * {@code "denied"}. Cross-platform wrappers that need a three-state model
     * (e.g. iOS parity with {@code notDetermined}) should track first-request state
     * in their own layer.
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
        PushEngage inst = instance;
        if (inst == null || inst.context == null) {
            return "denied";
        }

        // Check if notifications are enabled at the system level
        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(inst.context);
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
        PushEngage inst = instance;
        if (callback == null) {
            PELogger.error("Callback cannot be null for getSubscriptionStatus", null);
            return;
        }

        if (inst == null || inst.context == null) {
            callback.onFailure(null, "SDK not initialized");
            return;
        }

        if (inst.prefs == null) {
            callback.onFailure(null, "SDK preferences not initialized");
            return;
        }

        try {
            String siteStatus = inst.prefs.getSiteStatus();
            String notificationPermissionStatus = getNotificationPermissionStatus();
            boolean isManuallyUnsubscribed = inst.prefs.isManuallyUnsubscribed();
            boolean isSubscriberDeleted = inst.prefs.isSubscriberDeleted();
            String subscriberHash = inst.prefs.getHash();

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
        PushEngage inst = instance;
        if (callback == null) {
            PELogger.error("Callback cannot be null for getSubscriptionNotificationStatus", null);
            return;
        }

        if (inst == null || inst.context == null) {
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
        PushEngage inst = instance;
        if (callback == null) {
            PELogger.error("Callback cannot be null for getSubscriberId", null);
            return;
        }

        if (inst == null || inst.context == null) {
            callback.onFailure(null, "SDK not initialized");
            return;
        }

        if (inst.prefs == null) {
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
                        String subscriberHash = inst.prefs.getHash();

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
        PushEngage inst = instance;
        if (inst == null || inst.context == null) {
            if (callback != null) {
                callback.onFailure(null, "SDK not initialized");
            }
            return;
        }

        if (inst.prefs == null) {
            if (callback != null) {
                callback.onFailure(null, "Preferences not available");
            }
            return;
        }

        // Check if user has a valid subscription to unsubscribe from
        String hash = inst.prefs.getHash();
        if (TextUtils.isEmpty(hash)) {
            if (callback != null) {
                callback.onSuccess(true);
            }
            return;
        }

        try {
            // Set manually unsubscribed flag locally
            inst.prefs.setIsManuallyUnsubscribed(true);

            // Update server with unsubscribed status
            UpdateSubscriberStatusRequest request = new UpdateSubscriberStatusRequest(
                    inst.prefs.getSiteId(),
                    hash,
                    1L, // 1 = unsubscribed
                    inst.prefs.getDeleteOnNotificationDisable());

            Call<NetworkResponse> call = RestClient.getBackendClient(inst.context).updateSubscriberStatus(request);
            call.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(Call<NetworkResponse> call, Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        PELogger.debug("User unsubscribed successfully");
                        // Subscriber identity is no longer authoritative on this device.
                        inst.prefs.clearSubscriberFields();
                        if (callback != null) {
                            callback.onSuccess(true);
                        }
                    } else {
                        PELogger.error("Unsubscribe API failed with response code: " + response.code(), null);
                        // Revert local changes on API failure
                        inst.prefs.setIsManuallyUnsubscribed(false);
                        if (callback != null) {
                            callback.onFailure(response.code(), "Server error: " + response.code());
                        }
                    }
                }

                @Override
                public void onFailure(Call<NetworkResponse> call, Throwable t) {
                    PELogger.error("Unsubscribe API call failed", new Exception(t));
                    // Revert local changes on API failure
                    inst.prefs.setIsManuallyUnsubscribed(false);
                    if (callback != null) {
                        callback.onFailure(null, "Network error: " + t.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            PELogger.error("Error during unsubscribe", e);
            // Revert local changes on error
            inst.prefs.setIsManuallyUnsubscribed(false);
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
        PushEngage inst = instance;
        if (inst == null || inst.context == null) {
            if (callback != null) {
                callback.onFailure(null, "SDK not initialized");
            }
            return;
        }

        if (inst.prefs == null) {
            if (callback != null) {
                callback.onFailure(null, "Preferences not available");
            }
            return;
        }

        try {
            String permissionStatus = getNotificationPermissionStatus();
            String subscriberHash = inst.prefs.getHash();

            // If notification permission is granted AND we have subscriber data, call
            // update
            if ("granted".equals(permissionStatus) && !TextUtils.isEmpty(subscriberHash)) {
                PELogger.debug(
                        "Permission granted and subscriber data exists - calling updateSubscriberStatus(status: 0)");

                UpdateSubscriberStatusRequest request = new UpdateSubscriberStatusRequest(
                        inst.prefs.getSiteId(),
                        subscriberHash,
                        0L, // 0 = subscribed
                        inst.prefs.getDeleteOnNotificationDisable());

                Call<NetworkResponse> call = RestClient.getBackendClient(inst.context).updateSubscriberStatus(request);
                call.enqueue(new Callback<NetworkResponse>() {
                    @Override
                    public void onResponse(Call<NetworkResponse> call, Response<NetworkResponse> response) {
                        if (response.isSuccessful()) {
                            // Clear flags after successful subscription
                            inst.prefs.setIsManuallyUnsubscribed(false);
                            inst.prefs.setIsSubscriberDeleted(false);
                            PELogger.debug("Manual subscription successful via update");
                            if (callback != null) {
                                callback.onSuccess(true);
                            }
                        } else if (response.code() == 404) {
                            // Handle 404 by retrying add subscriber process
                            PELogger.debug("Received 404, retrying add subscriber process");
                            inst.prefs.setIsManuallyUnsubscribed(false);

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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        inst.peManager.automatedNotification(status, callback);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        inst.peManager.sendTriggerEvent(trigger, callback);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        inst.peManager.sendGoal(goal, callback);
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
     * Track Event
     *
     * This method allows you to send event data for a subscriber. These events can
     * be used to start or exit workflows.
     * You can create and manage workflows from the PushEngage Dashboard.
     *
     * @param event    The {@link TrackEvent} object containing the event payload.
     *                 - eventName: String (required) - The name of the event.
     *                 - provider: String (optional) - The provider name (defaults
     *                   to "PushEngage").
     *                 - eventType: String (optional) - The event type (defaults to
     *                   "PushEngage.CustomEvent").
     *                 - profileId: String (optional) - The profile ID of the
     *                   subscriber.
     *                 - data: Map&lt;String, Object&gt; (optional) - Custom data.
     *                   Values should be strings, numbers, or booleans.
     * @param callback A {@link PushEngageResponseCallback} for async result. May be
     *                 null if the caller does not care about the outcome.
     *
     *                 Example usage:
     *                 {@code
     *                 Map<String, Object> eventData = new HashMap<>();
     *                 eventData.put("product_id", "123");
     *                 eventData.put("product_name", "Product Name");
     *                 TrackEvent event = new TrackEvent("MySite.AddToCart", eventData);
     *
     *                 PushEngage.trackEvent(event, new PushEngageResponseCallback() {
     *                     @Override
     *                     public void onSuccess(Object responseObject) {
     *                         // Event tracked successfully
     *                     }
     *                     @Override
     *                     public void onFailure(Integer errorCode, String errorMessage) {
     *                         // Handle error
     *                     }
     *                 });
     *                 }
     */
    public static void trackEvent(TrackEvent event, PushEngageResponseCallback callback) {
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        if (event == null) {
            if (callback != null) callback.onFailure(400, "TrackEvent cannot be null");
            return;
        }
        inst.peManager.trackEvent(event, callback);
    }

    /**
     * Track Event (no callback).
     *
     * Fire-and-forget overload for {@link #trackEvent(TrackEvent, PushEngageResponseCallback)}.
     *
     * @param event The {@link TrackEvent} to send.
     */
    public static void trackEvent(TrackEvent event) {
        trackEvent(event, null);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        inst.peManager.addAlert(alert, callback);
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

    private static FirebaseOptions firebaseOptionsOrNull() {
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            return app != null ? app.getOptions() : null;
        } catch (Throwable t) {
            // FirebaseApp.getInstance() is documented to throw IllegalStateException
            // when Firebase isn't initialized; broaden the catch defensively so a
            // future Firebase SDK change can never crash the SDK sync callback.
            return null;
        }
    }

    private static void fireConfigError(int code, String message) {
        PELogger.error(message, null);
        FcmConfigErrorListener listener = fcmConfigErrorListener;
        if (listener != null) {
            try {
                listener.onFcmConfigError(code, message);
            } catch (Throwable t) {
                PELogger.error("FcmConfigErrorListener threw", t);
            }
        }
    }

    /**
     * Runs the FCM-config validator with the provided server values and the local
     * Firebase configuration. Logs and fires the listener for any mismatch.
     *
     * <p>Visible to internal callers in sibling packages (e.g. {@code DataWorker})
     * so the weekly sync can detect dashboard drift between subscribes. Not part
     * of the public consumer API — see {@code @RestrictTo}.</p>
     *
     * @return {@code true} when a mismatch was detected (caller may short-circuit),
     *         {@code false} for Match / Skipped / FirebaseUnavailable.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public static boolean runConfigValidation(String serverSenderId, String serverProjectId) {
        FcmConfigValidator.Result result = FcmConfigValidator.validate(
                serverSenderId, serverProjectId, firebaseOptionsOrNull());

        if (result instanceof FcmConfigValidator.Result.SenderMismatch) {
            FcmConfigValidator.Result.SenderMismatch r = (FcmConfigValidator.Result.SenderMismatch) result;
            fireConfigError(PEErrorCodes.FCM_SENDER_ID_MISMATCH,
                    "FCM sender ID mismatch: google-services.json reports '" + r.getLocal()
                            + "' but PushEngage dashboard has '" + r.getServer()
                            + "'. Push notifications will not be delivered. Verify the Sender ID on your PushEngage dashboard matches your google-services.json.");
            return true;
        }
        if (result instanceof FcmConfigValidator.Result.ProjectMismatch) {
            FcmConfigValidator.Result.ProjectMismatch r = (FcmConfigValidator.Result.ProjectMismatch) result;
            fireConfigError(PEErrorCodes.FCM_PROJECT_ID_MISMATCH,
                    "FCM project ID mismatch: google-services.json reports project_id='" + r.getLocal()
                            + "' but PushEngage dashboard's service-account JSON is for project_id='" + r.getServer()
                            + "'. Push notifications will not be delivered. Verify the service-account JSON uploaded to PushEngage belongs to the same Firebase project as your google-services.json.");
            return true;
        }
        if (result instanceof FcmConfigValidator.Result.BothMismatch) {
            FcmConfigValidator.Result.BothMismatch r = (FcmConfigValidator.Result.BothMismatch) result;
            fireConfigError(PEErrorCodes.FCM_CONFIG_BOTH_MISMATCH,
                    "FCM configuration mismatch: google-services.json reports sender_id='" + r.getLocalSender()
                            + "', project_id='" + r.getLocalProject()
                            + "' but PushEngage dashboard has sender_id='" + r.getServerSender()
                            + "', project_id='" + r.getServerProject()
                            + "'. Push notifications will not be delivered. Verify both the Sender ID and the service-account JSON on your PushEngage dashboard.");
            return true;
        }
        if (result instanceof FcmConfigValidator.Result.FirebaseUnavailable) {
            PELogger.debug("FCM config validation skipped: FirebaseApp not initialized");
            return false;
        }
        // Match or Skipped: nothing to do
        return false;
    }

    private static boolean isAppIdValid(String appId) {
        return appId != null && !appId.trim().isEmpty();
    }

    /**
     * Interprets a Firebase token-fetch failure. Fires {@code FCM_LOCAL_CONFIG_INVALID}
     * when Firebase Installations rejects the install with BAD_CONFIG (the
     * google-services.json is invalid for this app per Firebase's records); for
     * any other failure falls through to a generic error log so transient
     * failures aren't misclassified.
     */
    private static void handleFirebaseTokenFailure(Throwable ex) {
        if (ex instanceof FirebaseInstallationsException) {
            FirebaseInstallationsException fisEx = (FirebaseInstallationsException) ex;
            if (fisEx.getStatus() == FirebaseInstallationsException.Status.BAD_CONFIG) {
                fireConfigError(PEErrorCodes.FCM_LOCAL_CONFIG_INVALID,
                        "google-services.json appears to be invalid for this app: Firebase Installations rejected the request (BAD_CONFIG). Verify the file matches your applicationId and signing certificate.");
                return;
            }
        }
        PELogger.error("Firebase token retrieval failed", ex);
    }

    /**
     * Initiates an API call to synchronize the client device with data from the
     * server.
     * If successful, it triggers the subscription process or updating of subscriber
     * data based
     * on the site status and notification preferences.
     */
    private static void callAndroidSync() {
        PushEngage inst = requireInstance("callAndroidSync");
        if (inst == null) return;
        String siteKey = inst.prefs.getSiteKey();
        if (!isAppIdValid(siteKey)) {
            // Fail loud, fail local: never let the SDK fire /sites/{site_key}/sync/android
            // with an empty path segment. Do not increment retry counters — retrying a
            // misconfiguration helps no one and amplifies the request flood we're fixing.
            PELogger.error(
                    "App ID is not configured. Call PushEngage.Builder().setAppId(\"YOUR_APP_ID\") during app startup. Sync request skipped.",
                    null);
            return;
        }
        PELogger.debug("Sync for SiteKey = " + siteKey + " called");
        if (PEUtilities.checkNetworkConnection(inst.context)) {
            Call<AndroidSyncResponse> addRecordsResponseCall = RestClient.getBackendCdnClient(inst.context)
                    .androidSync(siteKey);
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
                            inst.prefs.setBackendUrl(androidSyncResponse.getData().getApi().getBackend());
                            inst.prefs.setBackendCdnUrl(androidSyncResponse.getData().getApi().getBackendCdn());
                            inst.prefs.setAnalyticsUrl(androidSyncResponse.getData().getApi().getAnalytics());
                            inst.prefs.setTriggerUrl(androidSyncResponse.getData().getApi().getTrigger());
                            inst.prefs.setOptinUrl(androidSyncResponse.getData().getApi().getOptin());
                            inst.prefs.setLoggerUrl(androidSyncResponse.getData().getApi().getLog());
                            inst.prefs.setSiteId(androidSyncResponse.getData().getSiteId());
                            inst.prefs.setProjectId(androidSyncResponse.getData().getFirebaseSenderId());
                            inst.prefs.setFirebaseProjectId(androidSyncResponse.getData().getFirebaseProjectId());
                            inst.prefs.setDeleteOnNotificationDisable(
                                    androidSyncResponse.getData().getDeleteOnNotificationDisable());
                            inst.prefs.setSiteStatus(androidSyncResponse.getData().getSiteStatus());
                            inst.prefs.setGeoFetch(androidSyncResponse.getData().getGeoLocationEnabled());
                            inst.prefs.setEu(androidSyncResponse.getData().getIsEu());

                            // Sync-time authoritative FCM-config check. On mismatch:
                            // logs, fires the listener, and skips the subscriber/add
                            // call below (the only thing that would create a
                            // permanently-undeliverable subscriber row on the server).
                            // Other local inst.prefs writes (isNotificationDisabled,
                            // isSubscriberDeleted) still run so the SDK's state
                            // reporting stays consistent.
                            boolean configMismatch = runConfigValidation(
                                    androidSyncResponse.getData().getFirebaseSenderId(),
                                    androidSyncResponse.getData().getFirebaseProjectId());

                            /*
                             * Checks if notifications are enabled and processes subscriber data
                             * accordingly.
                             */
                            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat
                                    .from(inst.context);
                            boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();
                            long longVal = areNotificationsEnabled ? 0 : 1;
                            inst.prefs.setIsNotificationDisabled(longVal);
                            if (!areNotificationsEnabled) {
                                if (!inst.prefs.getDeleteOnNotificationDisable()) {
                                    if (!configMismatch) {
                                        callAddSubscriberAPI();
                                    }
                                } else {
                                    inst.prefs.setIsSubscriberDeleted(true);
                                }
                            } else {
                                if (!configMismatch) {
                                    callAddSubscriberAPI();
                                }
                            }
                        } else {
                            /*
                             * Handles server response indicating non-active site status.
                             */
                            inst.prefs.setIsSubscriberDeleted(true);
                            PELogger.debug("Site Status = " + androidSyncResponse.getData().getSiteStatus());
                        }
                    } else {
                        inst.siteSyncRetryCount++;
                        if (inst.siteSyncRetryCount <= RETRY_COUNT) {
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
                    inst.siteSyncRetryCount++;
                    if (inst.siteSyncRetryCount <= RETRY_COUNT) {
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
            inst.siteSyncRetryCount++;
            if (inst.siteSyncRetryCount <= RETRY_COUNT) {
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
        PushEngage inst = requireInstance("isTablet");
        if (inst == null) return false;
        return inst.context.getResources().getBoolean(R.bool.is_tablet);
    }

    /**
     * Initiates an API call to add the client as a subscriber, sending necessary
     * device and
     * app information to the server for registration.
     */
    public static void callAddSubscriberAPI() {
        PushEngage inst = requireInstance("callAddSubscriberAPI");
        if (inst == null) return;
        String timeZone = PEUtilities.getTimeZone();
        String language = Locale.getDefault().getLanguage();
        String device = "";
        if (isTablet()) {
            device = PEConstants.TABLET;
        } else {
            device = PEConstants.MOBILE;
        }
        String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        String deviceModel = Build.MODEL;
        String deviceManufacturer = Build.MANUFACTURER;
        String deviceVersion = Build.VERSION.RELEASE;
        String packageName = inst.context.getPackageName();

        int width = Resources.getSystem().getDisplayMetrics().widthPixels;
        int height = Resources.getSystem().getDisplayMetrics().heightPixels;
        String screenSize = width + "*" + height;

        AddSubscriberRequest addSubscriberRequest = new AddSubscriberRequest();
        AddSubscriberRequest.Subscription subscription = addSubscriberRequest.new Subscription(inst.prefs.getDeviceToken(),
                String.valueOf(inst.prefs.getProjectId()));
        addSubscriberRequest = new AddSubscriberRequest(inst.prefs.getSiteId(), subscription, PEConstants.ANDROID, device,
                deviceVersion, deviceModel, deviceManufacturer, timeZone, language, deviceName, screenSize, packageName,
                inst.prefs.isNotificationDisabled());
        if (PEUtilities.checkNetworkConnection(inst.context)) {
            Call<AddSubscriberResponse> addSubscriberResponseCall = RestClient.getBackendClient(inst.context).addSubscriber(
                    addSubscriberRequest, getSdkVersion(), String.valueOf(inst.prefs.getEu()),
                    String.valueOf(inst.prefs.isGeoFetch()));
            addSubscriberResponseCall.enqueue(new Callback<AddSubscriberResponse>() {
                @Override
                public void onResponse(@NonNull Call<AddSubscriberResponse> call,
                        @NonNull Response<AddSubscriberResponse> response) {
                    if (response.isSuccessful()) {
                        AddSubscriberResponse addSubscriberResponse = response.body();
                        inst.prefs.setHash(addSubscriberResponse.getData().getSubscriberHash());
                        inst.prefs.setIsSubscriberDeleted(false);
                        inst.prefs.setIsManuallyUnsubscribed(false);
                        enableWeeklyDataSync();
                        enableDailyDataSync();
                    } else {
                        inst.addSubscribeRetryCount++;
                        if (inst.addSubscribeRetryCount <= RETRY_COUNT) {
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
                                    inst.prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                            errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                            errorLogRequest.setName(PEConstants.RECORD_SUBSCRIPTION_FAILED);
                            errorLogRequest.setData(data);
                            PEUtilities.addLogs(inst.context, TAG, errorLogRequest);
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<AddSubscriberResponse> call, @NonNull Throwable t) {
                    inst.addSubscribeRetryCount++;
                    if (inst.addSubscribeRetryCount <= RETRY_COUNT) {
                        final Callback<AddSubscriberResponse> callback = this;
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                call.clone().enqueue(callback);
                            }
                        }, DELAY);// 3 minutes delay
                    } else {
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        ErrorLogRequest.Data data = errorLogRequest.new Data("callAddSubscriberAPI", inst.prefs.getHash(),
                                PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                        errorLogRequest.setName(PEConstants.RECORD_SUBSCRIPTION_FAILED);
                        errorLogRequest.setData(data);
                        PEUtilities.addLogs(inst.context, TAG, errorLogRequest);
                    }
                }
            });
        } else {
            inst.addSubscribeRetryCount++;
            if (inst.addSubscribeRetryCount <= RETRY_COUNT) {
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
        PushEngage inst = requireInstance("getLastNotificationPayload");
        if (inst == null) return "";
        return inst.prefs.getPayload();
    }

    /**
     * Enables a weekly synchronization task for subscriber data. This method
     * schedules a periodic
     * work request to run the {@link WeeklySyncDataWorker} at regular intervals.
     * The worker performs data synchronization tasks related to subscribers on a
     * weekly basis.
     */
    private static void enableWeeklyDataSync() {
        PushEngage inst = requireInstance("enableWeeklyDataSync");
        if (inst == null) return;
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

        WorkManager.getInstance(inst.context).enqueueUniquePeriodicWork(
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
        PushEngage inst = requireInstance("enableDailyDataSync");
        if (inst == null) return;
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
        WorkManager.getInstance(inst.context).enqueueUniquePeriodicWork(
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            // Convert List<String> to comma-separated string
            String fieldsString = String.join(",", values);
            Call<NetworkResponse> subscriberDetailsResponseCall = RestClient.getBackendClient(inst.context)
                    .subscriberDetails(inst.prefs.getHash(), fieldsString);
            subscriberDetailsResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse genericResponse = response.body();
                        if (callback != null && genericResponse != null)
                            callback.onSuccess(genericResponse.getData());
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> getSubscriberAttributesResponseCall = RestClient.getBackendClient(inst.context)
                    .getSubscriberAttributes(inst.prefs.getHash());
            getSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        NetworkResponse genericResponse = response.body();
                        if (callback != null && genericResponse != null)
                            callback.onSuccess(genericResponse.getData());
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> deleteSubscriberAttributesResponseCall = RestClient.getBackendClient(inst.context)
                    .deleteSubscriberAttributes(inst.prefs.getHash(), values);
            deleteSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<NetworkResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(inst.context)
                    .addAttributes(inst.prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = (JsonObject) jsonParser.parse(obj.toString());
            Call<NetworkResponse> addSubscriberAttributesResponseCall = RestClient.getBackendClient(inst.context)
                    .setAttributes(inst.prefs.getHash(), jsonObject);
            addSubscriberAttributesResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddProfileIdRequest addProfileIdRequest = new AddProfileIdRequest(
                    inst.prefs.getHash(), profileId, inst.prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> addProfileIdResponseCall = RestClient.getBackendClient(inst.context)
                    .addProfileId(addProfileIdRequest);
            addProfileIdResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        // Keep the subscriber-fields cache in sync so a later
                        // identify({profile_id: x}) can short-circuit.
                        inst.prefs.mergeSubscriberFields(
                                java.util.Collections.<String, Object>singletonMap("profile_id", profileId));
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
     * Upserts the predefined personal fields for the current subscriber.
     *
     * Mirrors the Web SDK {@code identify(subscriberFields)} method. Valid keys
     * are restricted to the 12 predefined subscriber fields: first_name,
     * last_name, email, phone, gender, dob, language, profile_id, country,
     * city, state, zip. Values must be String, Number, or Boolean. A numeric
     * profile_id is automatically coerced to its String form before the call.
     *
     * This method does not provide a callback for API response handling.
     *
     * @param fields A {@link JSONObject} of subscriber fields to upsert.
     */
    public static void identify(JSONObject fields) {
        identify(fields, null);
    }

    /**
     * Upserts the predefined personal fields for the current subscriber and
     * delivers the result asynchronously.
     *
     * If every requested field already matches the locally cached value, the
     * call short-circuits with {@code callback.onSuccess(null)} and no
     * network request is sent.
     *
     * @param fields   A {@link JSONObject} of subscriber fields to upsert.
     * @param callback A {@link PushEngageResponseCallback} for result delivery.
     *                 May be null.
     */
    public static void identify(JSONObject fields, PushEngageResponseCallback callback) {
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        PESubscriberFieldsHandler.INSTANCE.identify(inst.context, inst.prefs, fields, callback);
    }

    /**
     * Removes a set of predefined personal fields from the current subscriber.
     *
     * Mirrors the Web SDK {@code logout(subscriberFieldNames)} method. Passing
     * {@code null} or an empty list defaults to the PII field set:
     * {@code [first_name, last_name, email, phone, gender, dob, profile_id]}.
     *
     * This method does not provide a callback for API response handling.
     *
     * @param fieldNames A list of field names to remove. {@code null} or empty
     *                   triggers the PII default set.
     */
    public static void logout(List<String> fieldNames) {
        logout(fieldNames, null);
    }

    /**
     * Removes a set of predefined personal fields from the current subscriber
     * and delivers the result asynchronously.
     *
     * If none of the requested field names exist in the locally cached
     * subscriber data, the call short-circuits with
     * {@code callback.onSuccess(null)} and no network request is sent.
     *
     * @param fieldNames A list of field names to remove. {@code null} or empty
     *                   triggers the PII default set.
     * @param callback   A {@link PushEngageResponseCallback} for result
     *                   delivery. May be null.
     */
    public static void logout(List<String> fieldNames, PushEngageResponseCallback callback) {
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        PESubscriberFieldsHandler.INSTANCE.logout(inst.context, inst.prefs, fieldNames, callback);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddSegmentRequest addSegmentRequest = new AddSegmentRequest(inst.prefs.getHash(), segmentId, inst.prefs.getSiteId(),
                    PEConstants.ANDROID);
            Call<NetworkResponse> addSegmentResponseCall = RestClient.getBackendClient(inst.context)
                    .addSegments(addSegmentRequest);
            addSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            RemoveSegmentRequest removeSegmentRequest = new RemoveSegmentRequest(
                    inst.prefs.getHash(), segmentId, inst.prefs.getSiteId(), PEConstants.ANDROID);
            Call<NetworkResponse> removeSegmentResponseCall = RestClient.getBackendClient(inst.context)
                    .removeSegments(removeSegmentRequest);
            removeSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            AddDynamicSegmentRequest addDynamicSegmentRequest = new AddDynamicSegmentRequest(inst.prefs.getHash(),
                    inst.prefs.getSiteId(), PEConstants.ANDROID, segments);
            Call<NetworkResponse> addDynamicSegmentResponseCall = RestClient.getBackendClient(inst.context)
                    .addDynamicSegments(addDynamicSegmentRequest);
            addDynamicSegmentResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            SegmentHashArrayRequest segmentHashArrayRequest = new SegmentHashArrayRequest(inst.prefs.getHash(),
                    inst.prefs.getSiteId(), segmentId);
            Call<NetworkResponse> segmentHashArrayResponseCall = RestClient.getBackendClient(inst.context)
                    .getSegmentHashArray(segmentHashArrayRequest);
            segmentHashArrayResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
        PushEngage inst = requireInstance(callback);
        if (inst == null) return;
        String validationResult = PEUtilities.apiPreValidate(inst.context);
        if (validationResult.equalsIgnoreCase(PEConstants.VALID)) {
            Call<NetworkResponse> checkSubscriberHashResponseCall = RestClient.getBackendClient(inst.context)
                    .checkSubscriberHash(inst.prefs.getHash());
            checkSubscriberHashResponseCall.enqueue(new Callback<NetworkResponse>() {
                @Override
                public void onResponse(@NonNull Call<NetworkResponse> call,
                        @NonNull Response<NetworkResponse> response) {
                    if (response.isSuccessful()) {
                        if (callback != null)
                            callback.onSuccess(null);
                    } else {
                        dispatchHttpFailure(response, callback, inst.context);
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
