package com.pushengage.pushengage.helper;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final String KEY_SITE_STATUS = "siteStatus";
    private static final String KEY_GEO_FETCH = "geoFetch";
    private static final String KEY_IS_EU = "isEu";
    private static final String KEY_DELETE_ON_NOTIFICATION_DISABLE = "delete_on_notification_disable";
    private static final String KEY_ARE_NOTIFICATIONS_DISABLED = "is_notifications_disabled";
    private static final String KEY_SUBSCRIBER_DELETED = "subscriberDeleted";
    private static final String KEY_ACTION_BUTTON_RECEIVER_REGISTERED = "KEY_ACTION_BUTTON_RECEIVER_REGISTERED";
    private static final String KEY_SMALL_ICON_RESOURCE = "KEY_SMALL_ICON_RESOURCE";
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

    public String getProjectId() {
        return mPrefsRead.getString(KEY_PROJECT_ID, "");
    }

    public void setProjectId(String projectId) {
        mPrefsWrite.putString(KEY_PROJECT_ID, projectId);
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

}
