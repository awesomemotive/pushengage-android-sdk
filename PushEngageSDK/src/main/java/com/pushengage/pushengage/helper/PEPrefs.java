package com.pushengage.pushengage.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Raasesh on 11/04/21.
 */

public class PEPrefs {
    private static final String KEY_DEVICE_TOKEN = "deviceToken";
    private static final String KEY_HASH = "hash";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_SITE_KEY = "siteKey";
    private static final String KEY_BACKEND_URL = "backend";
    private static final String KEY_BACKEND_CDN_URL = "backendCdn";
    private static final String KEY_ANALYTICS_URL = "analytics";
    private static final String KEY_TRIGGER_URL = "trigger";
    private static final String KEY_OPTIN_URL = "optin";
    private static final String KEY_LOGGER_URL = "log";
    private static final String KEY_SITE_ID = "siteId";
    private static final String KEY_PROJECT_ID = "projectId";
    private static final String KEY_FIREBASE_PROJECT_ID = "firebaseProjectId";
    private static final String KEY_SITE_STATUS = "siteStatus";
    private static final String KEY_GEO_FETCH = "geoFetch";
    private static final String KEY_IS_EU = "isEu";
    private static final String KEY_DELETE_ON_NOTIFICATION_DISABLE = "delete_on_notification_disable";
    private static final String KEY_ARE_NOTIFICATIONS_DISABLED = "is_notifications_disabled";
    private static final String KEY_SUBSCRIBER_DELETED = "subscriberDeleted";
    private static final String KEY_IS_MANUALLY_UNSUBSCRIBED = "is_manually_unsubscribed";
    private static final String KEY_ACTION_BUTTON_RECEIVER_REGISTERED = "ACTION_BUTTON_RECEIVER_REGISTERED";
    private static final String KEY_SMALL_ICON_RESOURCE = "SMALL_ICON_RESOURCE";
    private static final String KEY_ENVIRONMENT = "ENVIRONMENT";
    private static final String KEY_BADGE_COUNT = "badge_count";
    private static final String KEY_PLATFORM = "platform";
    private static final String KEY_WRAPPER_VERSION = "wrapperVersion";
    private static final String KEY_SUBSCRIBER_CACHE = "subscriber_cache";
    private static final String KEY_SUBSCRIBER_CACHE_TIMESTAMP = "subscriber_cache_timestamp";
    private final SharedPreferences mPrefsRead;
    private final SharedPreferences.Editor mPrefsWrite;

    public PEPrefs(Context context) {
        final String PREFS = "PushEngage";
        mPrefsRead = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mPrefsWrite = mPrefsRead.edit();
    }

    public String getDeviceToken() {
        return mPrefsRead.getString(KEY_DEVICE_TOKEN, "");
    }

    public void setDeviceToken(String deviceToken) {
        mPrefsWrite.putString(KEY_DEVICE_TOKEN, deviceToken);
        mPrefsWrite.commit();
    }

    public String getHash() {
        return mPrefsRead.getString(KEY_HASH, "");
    }

    public void setHash(String hash) {
        mPrefsWrite.putString(KEY_HASH, hash);
        mPrefsWrite.commit();
    }

    public String getPayload() {
        return mPrefsRead.getString(KEY_PAYLOAD, "");
    }

    public void setPayload(String payload) {
        mPrefsWrite.putString(KEY_PAYLOAD, payload);
        mPrefsWrite.commit();
    }

    public String getSiteKey() {
        return mPrefsRead.getString(KEY_SITE_KEY, "");
    }

    public void setSiteKey(String siteKey) {
        mPrefsWrite.putString(KEY_SITE_KEY, siteKey);
        mPrefsWrite.commit();
    }

    public String getBackendUrl() {
        return mPrefsRead.getString(KEY_BACKEND_URL, "");
    }

    public void setBackendUrl(String backendUrl) {
        mPrefsWrite.putString(KEY_BACKEND_URL, backendUrl);
        mPrefsWrite.commit();
    }

    public String getBackendCdnUrl() {
        return mPrefsRead.getString(KEY_BACKEND_CDN_URL, "");
    }

    public void setBackendCdnUrl(String backendCdnUrl) {
        mPrefsWrite.putString(KEY_BACKEND_CDN_URL, backendCdnUrl);
        mPrefsWrite.commit();
    }

    public String getAnalyticsUrl() {
        return mPrefsRead.getString(KEY_ANALYTICS_URL, "");
    }

    public void setAnalyticsUrl(String analyticsUrl) {
        mPrefsWrite.putString(KEY_ANALYTICS_URL, analyticsUrl);
        mPrefsWrite.commit();
    }

    public String getTriggerUrl() {
        return mPrefsRead.getString(KEY_TRIGGER_URL, "");
    }

    public void setTriggerUrl(String triggerUrl) {
        mPrefsWrite.putString(KEY_TRIGGER_URL, triggerUrl);
        mPrefsWrite.commit();
    }

    public String getOptinUrl() {
        return mPrefsRead.getString(KEY_OPTIN_URL, "");
    }

    public void setOptinUrl(String optinUrl) {
        mPrefsWrite.putString(KEY_OPTIN_URL, optinUrl);
        mPrefsWrite.commit();
    }

    public String getLoggerUrl() {
        return mPrefsRead.getString(KEY_LOGGER_URL, "");
    }

    public void setLoggerUrl(String loggerUrl) {
        mPrefsWrite.putString(KEY_LOGGER_URL, loggerUrl);
        mPrefsWrite.commit();
    }

    public Long getSiteId() {
        return mPrefsRead.getLong(KEY_SITE_ID, 0);
    }

    public void setSiteId(Long siteId) {
        mPrefsWrite.putLong(KEY_SITE_ID, siteId);
        mPrefsWrite.commit();
    }

    /**
     * Returns the cached Firebase sender ID (also known as the GCM/FCM
     * project number). Despite the misleading name, this stores the
     * {@code firebase_sender_id} value from the Android sync response;
     * the Firebase project_id slug is held by {@link #getFirebaseProjectId()}.
     * The name predates the project_id field and is kept as-is to avoid
     * migrating existing SharedPreferences keys on upgrade.
     */
    public String getProjectId() {
        return mPrefsRead.getString(KEY_PROJECT_ID, "");
    }

    public void setProjectId(String projectId) {
        mPrefsWrite.putString(KEY_PROJECT_ID, projectId);
        mPrefsWrite.commit();
    }

    public String getFirebaseProjectId() {
        return mPrefsRead.getString(KEY_FIREBASE_PROJECT_ID, "");
    }

    public void setFirebaseProjectId(String firebaseProjectId) {
        mPrefsWrite.putString(KEY_FIREBASE_PROJECT_ID, firebaseProjectId);
        mPrefsWrite.commit();
    }

    public String getSiteStatus() {
        return mPrefsRead.getString(KEY_SITE_STATUS, "");
    }

    public void setSiteStatus(String siteStatus) {
        mPrefsWrite.putString(KEY_SITE_STATUS, siteStatus);
        mPrefsWrite.commit();
    }

    public Boolean getDeleteOnNotificationDisable() {
        return mPrefsRead.getBoolean(KEY_DELETE_ON_NOTIFICATION_DISABLE, false);
    }

    public void setDeleteOnNotificationDisable(Boolean unsubscribe) {
        mPrefsWrite.putBoolean(KEY_DELETE_ON_NOTIFICATION_DISABLE, unsubscribe);
        mPrefsWrite.commit();
    }

    public Long isNotificationDisabled() {
        return mPrefsRead.getLong(KEY_ARE_NOTIFICATIONS_DISABLED, 0);
    }

    public void setIsNotificationDisabled(Long isNotificationEnabled) {
        mPrefsWrite.putLong(KEY_ARE_NOTIFICATIONS_DISABLED, isNotificationEnabled);
        mPrefsWrite.commit();
    }

    public Boolean isSubscriberDeleted() {
        return mPrefsRead.getBoolean(KEY_SUBSCRIBER_DELETED, false);
    }

    public void setIsSubscriberDeleted(Boolean isSubscriberDeleted) {
        mPrefsWrite.putBoolean(KEY_SUBSCRIBER_DELETED, isSubscriberDeleted);
        mPrefsWrite.commit();
    }

    public Boolean isManuallyUnsubscribed() {
        return mPrefsRead.getBoolean(KEY_IS_MANUALLY_UNSUBSCRIBED, false);
    }

    public void setIsManuallyUnsubscribed(Boolean isManuallyUnsubscribed) {
        mPrefsWrite.putBoolean(KEY_IS_MANUALLY_UNSUBSCRIBED, isManuallyUnsubscribed);
        mPrefsWrite.commit();
    }

    public Boolean isGeoFetch() {
        return mPrefsRead.getBoolean(KEY_GEO_FETCH, false);
    }

    public void setGeoFetch(Boolean isGeoFetch) {
        mPrefsWrite.putBoolean(KEY_GEO_FETCH, isGeoFetch);
        mPrefsWrite.commit();
    }

    public Long getEu() {
        return mPrefsRead.getLong(KEY_IS_EU, 0);
    }

    public void setEu(Long eu) {
        mPrefsWrite.putLong(KEY_IS_EU, eu);
        mPrefsWrite.commit();
    }

    public Boolean isActionButtonReceiverRegistered() {
        return mPrefsRead.getBoolean(KEY_ACTION_BUTTON_RECEIVER_REGISTERED, false);
    }

    public void setActionButtonReceiverRegistered(Boolean isActionButtonReceiverRegistered) {
        mPrefsWrite.putBoolean(KEY_ACTION_BUTTON_RECEIVER_REGISTERED, isActionButtonReceiverRegistered);
        mPrefsWrite.commit();
    }

    public String getSmallIconResource() {
        return mPrefsRead.getString(KEY_SMALL_ICON_RESOURCE, "ic_stat_notification_default");
    }

    public void setSmallIconResource(String smallIconResource) {
        mPrefsWrite.putString(KEY_SMALL_ICON_RESOURCE, smallIconResource);
        mPrefsWrite.commit();
    }

    public String getEnvironment() {
        return mPrefsRead.getString(KEY_ENVIRONMENT, PEConstants.PROD);
    }

    public void setEnvironment(String environment) {
        mPrefsWrite.putString(KEY_ENVIRONMENT, environment);
        mPrefsWrite.commit();
    }

    public int getBadgeCount() {
        return mPrefsRead.getInt(KEY_BADGE_COUNT, 0);
    }

    public void setBadgeCount(int badgeCount) {
        mPrefsWrite.putInt(KEY_BADGE_COUNT, badgeCount);
        mPrefsWrite.commit();
    }

    public String getPlatform() {
        return mPrefsRead.getString(KEY_PLATFORM, PEPlatform.ANDROID);
    }

    public void setPlatform(String platform) {
        mPrefsWrite.putString(KEY_PLATFORM, PEUtilities.sanitizeUaSegment(platform, PEPlatform.ANDROID));
        mPrefsWrite.commit();
    }

    public String getWrapperVersion() {
        return mPrefsRead.getString(KEY_WRAPPER_VERSION, "");
    }

    public void setWrapperVersion(String wrapperVersion) {
        mPrefsWrite.putString(KEY_WRAPPER_VERSION, PEUtilities.sanitizeUaSegment(wrapperVersion, ""));
        mPrefsWrite.commit();
    }

    /**
     * Wipes cached state that belongs to a specific PushEngage site (sync response
     * payload + the subscriber row created against it). Called from the Builder
     * when the App ID changes between builds so an unsubscribe/getSubscriberId
     * issued before the next sync can't act on the previous site's identifiers.
     * <p>
     * Preserves device-scoped state: deviceToken, OS notification permission flag,
     * host-app config (small icon, badge count, environment).
     */
    public void clearSiteSpecificData() {
        mPrefsWrite.remove(KEY_HASH);
        mPrefsWrite.remove(KEY_BACKEND_URL);
        mPrefsWrite.remove(KEY_BACKEND_CDN_URL);
        mPrefsWrite.remove(KEY_ANALYTICS_URL);
        mPrefsWrite.remove(KEY_TRIGGER_URL);
        mPrefsWrite.remove(KEY_OPTIN_URL);
        mPrefsWrite.remove(KEY_LOGGER_URL);
        mPrefsWrite.remove(KEY_SITE_ID);
        mPrefsWrite.remove(KEY_PROJECT_ID);
        mPrefsWrite.remove(KEY_FIREBASE_PROJECT_ID);
        mPrefsWrite.remove(KEY_SITE_STATUS);
        mPrefsWrite.remove(KEY_DELETE_ON_NOTIFICATION_DISABLE);
        mPrefsWrite.remove(KEY_GEO_FETCH);
        mPrefsWrite.remove(KEY_IS_EU);
        mPrefsWrite.remove(KEY_SUBSCRIBER_DELETED);
        mPrefsWrite.remove(KEY_IS_MANUALLY_UNSUBSCRIBED);
        mPrefsWrite.remove(KEY_SUBSCRIBER_CACHE);
        mPrefsWrite.remove(KEY_SUBSCRIBER_CACHE_TIMESTAMP);
        mPrefsWrite.commit();
    }

    /**
     * Returns the cached subscriber fields as an immutable Map. Empty map when
     * no values have been cached. Backed by a JSON blob in SharedPreferences
     * under {@link #KEY_SUBSCRIBER_CACHE}.
     */
    public Map<String, Object> getSubscriberFields() {
        String raw = mPrefsRead.getString(KEY_SUBSCRIBER_CACHE, "");
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            JSONObject obj = new JSONObject(raw);
            Map<String, Object> out = new LinkedHashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, obj.get(k));
            }
            return out;
        } catch (JSONException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Upserts the given fields into the cache. Existing keys are overwritten;
     * untouched keys are preserved.
     */
    public void mergeSubscriberFields(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Map<String, Object> current = new LinkedHashMap<>(getSubscriberFields());
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            current.put(e.getKey(), e.getValue());
        }
        writeCache(current);
    }

    /**
     * Removes the named keys from the cache. No-op for keys not present.
     */
    public void removeSubscriberFields(List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return;
        }
        Map<String, Object> current = new LinkedHashMap<>(getSubscriberFields());
        if (current.isEmpty()) {
            return;
        }
        for (String name : fieldNames) {
            current.remove(name);
        }
        writeCache(current);
    }

    /** Wipes the entire subscriber-fields cache and its freshness timestamp. */
    public void clearSubscriberFields() {
        mPrefsWrite.remove(KEY_SUBSCRIBER_CACHE);
        mPrefsWrite.remove(KEY_SUBSCRIBER_CACHE_TIMESTAMP);
        mPrefsWrite.commit();
    }

    /**
     * Returns the wall-clock timestamp (millis) of the last successful cache
     * write, or 0L when the cache has never been written or has been cleared.
     * Consumers use this to bound how long they trust the cache before forcing
     * a server round-trip.
     */
    public long getSubscriberCacheTimestamp() {
        return mPrefsRead.getLong(KEY_SUBSCRIBER_CACHE_TIMESTAMP, 0L);
    }

    private void writeCache(Map<String, Object> values) {
        if (values.isEmpty()) {
            mPrefsWrite.remove(KEY_SUBSCRIBER_CACHE);
            mPrefsWrite.remove(KEY_SUBSCRIBER_CACHE_TIMESTAMP);
        } else {
            mPrefsWrite.putString(KEY_SUBSCRIBER_CACHE, new JSONObject(values).toString());
            mPrefsWrite.putLong(KEY_SUBSCRIBER_CACHE_TIMESTAMP, System.currentTimeMillis());
        }
        mPrefsWrite.commit();
    }

}
