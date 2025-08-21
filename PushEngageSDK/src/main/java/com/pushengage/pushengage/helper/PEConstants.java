package com.pushengage.pushengage.helper;

public class PEConstants {
    public static final String SDK_VERSION = "0.0.6";

    public static final String PROD = "PRODUCTION";
    public static final String STG = "STAGING";

    public static final String BASE = "BASE";
    public static final String BASE_CDN = "BASE_CDN";
    public static final String TRIGGER = "TRIGGER";
    public static final String LOG = "LOG";
    public static final String ANALYTICS = "ANALYTICS";

    public static final String WEEKLY_SYNC_DATA = "WEEKLY_SYNC_DATA";
    public static final String DAILY_SYNC_DATA = "DAILY_SYNC_DATA";

    public static final String MOBILE = "mobile";
    public static final String TABLET = "tablet";
    public static final String ANDROID_SDK = "android-sdk";
    public static final String ANDROID = "android";

    public static final String RECORD_SUBSCRIPTION_FAILED = "recordSubscriptionFailed";
    public static final String NOTIFICATION_REFETCH_FAILED = "notificationRefetchFailed";
    public static final String VIEW_COUNT_TRACKING_FAILED = "viewCountTrackingFailed";
    public static final String CLICK_COUNT_TRACKING_FAILED = "clickCountTrackingFailed";

    public static final String ACTIVE = "active";
    public static final String SITE_NOT_ACTIVE = "Site not active";
    public static final String USER_NOT_SUBSCRIBED = "User not subscribed";
    public static final String VALID = "VALID";
    public static final String NETWORK_ISSUE = "Internet Not Available";

    public static final Integer RETRY_DELAY = 60000;

    public static final String DEFAULT_CHANNEL_ID = "Default Channel";
    public static final String DEFAULT_CHANNEL_NAME = "Default Channel";

    public static final String STG_BASE_CDN_URL = "https://staging-dexter.pushengage.com/p/v1/";
    public static final String STG_BASE_URL = "https://staging-dexter.pushengage.com/p/v1/";
    public static final String STG_ANALYTICS_URL = "https://staging-dexter.pushengage.com/p/v1/";
    public static final String STG_TRIGGER_URL = "https://x9dlvh1zcg.execute-api.us-east-1.amazonaws.com/beta/streams/staging-trigger/records/";
    public static final String STG_LOG_URL = "https://notify.pushengage.com/v1/";

    public static final String PROD_BASE_CDN_URL = "https://dexter-cdn.pushengage.com/p/v1/";
    public static final String PROD_BASE_URL = "https://clients-api.pushengage.com/p/v1/";
    public static final String PROD_ANALYTICS_URL = "https://noti-analytics.pushengage.com/p/v1/";
    public static final String PROD_TRIGGER_URL = "https://m4xrk918t5.execute-api.us-east-1.amazonaws.com/beta/streams/production_triggers/records/";
    public static final String PROD_LOG_URL = "https://notify.pushengage.com/v1/";

    //Intent extras
    public static final String URL_EXTRA = "url";
    public static final String TAG_EXTRA = "tag";
    public static final String DATA_EXTRA = "data";
    public static final String ID_EXTRA = "id";
    public static final String ACTION_EXTRA = "action";


}
