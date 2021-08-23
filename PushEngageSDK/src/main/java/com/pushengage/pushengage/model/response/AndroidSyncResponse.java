package com.pushengage.pushengage.model.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class AndroidSyncResponse {

    public class Api {

        @SerializedName("backend")
        @Expose
        private String backend;
        @SerializedName("backend_cdn")
        @Expose
        private String backendCdn;
        @SerializedName("analytics")
        @Expose
        private String analytics;
        @SerializedName("trigger")
        @Expose
        private String trigger;
        @SerializedName("optin")
        @Expose
        private String optin;
        @SerializedName("log")
        @Expose
        private String log;

        /**
         * No args constructor for use in serialization
         */
        public Api() {
        }

        /**
         * @param analytics
         * @param log
         * @param optin
         * @param backend
         * @param trigger
         */
        public Api(String backend, String backendCdn, String analytics, String trigger, String optin, String log) {
            super();
            this.backend = backend;
            this.backendCdn =backendCdn;
            this.analytics = analytics;
            this.trigger = trigger;
            this.optin = optin;
            this.log = log;
        }

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public String getBackendCdn() {
            return backendCdn;
        }

        public void setBackendCdn(String backendCdn) {
            this.backendCdn = backendCdn;
        }

        public String getAnalytics() {
            return analytics;
        }

        public void setAnalytics(String analytics) {
            this.analytics = analytics;
        }

        public String getTrigger() {
            return trigger;
        }

        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }

        public String getOptin() {
            return optin;
        }

        public void setOptin(String optin) {
            this.optin = optin;
        }

        public String getLog() {
            return log;
        }

        public void setLog(String log) {
            this.log = log;
        }

    }

    public class Data {

        @SerializedName("site_id")
        @Expose
        private Long siteId;
        @SerializedName("site_status")
        @Expose
        private String siteStatus;
        @SerializedName("site_name")
        @Expose
        private String siteName;
        @SerializedName("site_subdomain")
        @Expose
        private String siteSubdomain;
        @SerializedName("is_eu")
        @Expose
        private Long isEu;
        @SerializedName("is_sponsored")
        @Expose
        private Long isSponsored;
        @SerializedName("firebase_sender_id")
        @Expose
        private String firebaseSenderId;
        @SerializedName("geo_fetch")
        @Expose
        private Boolean geoFetch;
        @SerializedName("api")
        @Expose
        private Api api;
        @SerializedName("delete_on_notification_disable")
        @Expose
        private Boolean deleteOnNotificationDisable;

        /**
         * No args constructor for use in serialization
         */
        public Data() {
        }

        /**
         * @param firebaseSenderId
         * @param siteStatus
         * @param siteId
         * @param siteName
         * @param isEu
         * @param api
         * @param siteSubdomain
         * @param isSponsored
         * @param geoFetch
         */
        public Data(Long siteId, String siteStatus, String siteName, String siteSubdomain, Long isEu, Long isSponsored, String firebaseSenderId, Boolean geoFetch, Api api, Boolean deleteOnNotificationDisable) {
            super();
            this.siteId = siteId;
            this.siteStatus = siteStatus;
            this.siteName = siteName;
            this.siteSubdomain = siteSubdomain;
            this.isEu = isEu;
            this.isSponsored = isSponsored;
            this.firebaseSenderId = firebaseSenderId;
            this.geoFetch = geoFetch;
            this.api = api;
            this.deleteOnNotificationDisable = deleteOnNotificationDisable;
        }

        public Long getSiteId() {
            return siteId;
        }

        public void setSiteId(Long siteId) {
            this.siteId = siteId;
        }

        public String getSiteStatus() {
            return siteStatus;
        }

        public void setSiteStatus(String siteStatus) {
            this.siteStatus = siteStatus;
        }

        public String getSiteName() {
            return siteName;
        }

        public void setSiteName(String siteName) {
            this.siteName = siteName;
        }

        public String getSiteSubdomain() {
            return siteSubdomain;
        }

        public void setSiteSubdomain(String siteSubdomain) {
            this.siteSubdomain = siteSubdomain;
        }

        public Long getIsEu() {
            return isEu;
        }

        public void setIsEu(Long isEu) {
            this.isEu = isEu;
        }

        public Long getIsSponsored() {
            return isSponsored;
        }

        public void setIsSponsored(Long isSponsored) {
            this.isSponsored = isSponsored;
        }

        public String getFirebaseSenderId() {
            return firebaseSenderId;
        }

        public void setFirebaseSenderId(String firebaseSenderId) {
            this.firebaseSenderId = firebaseSenderId;
        }

        public Boolean getGeoLocationEnabled() {
            return geoFetch;
        }

        public void setGeoLocationEnabled(Boolean geoFetch) {
            this.geoFetch = geoFetch;
        }

        public Api getApi() {
            return api;
        }

        public void setApi(Api api) {
            this.api = api;
        }

        public Boolean getDeleteOnNotificationDisable() {
            return deleteOnNotificationDisable;
        }

        public void setDeleteOnNotificationDisable(Boolean deleteOnNotificationDisable) {
            this.deleteOnNotificationDisable = deleteOnNotificationDisable;
        }

    }

    @SerializedName("error_code")
    @Expose
    private Long errorCode;
    @SerializedName("data")
    @Expose
    private Data data;

    /**
     * No args constructor for use in serialization
     */
    public AndroidSyncResponse() {
    }

    /**
     * @param data
     * @param errorCode
     */
    public AndroidSyncResponse(Long errorCode, Data data) {
        super();
        this.errorCode = errorCode;
        this.data = data;
    }

    public Long getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Long errorCode) {
        this.errorCode = errorCode;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

}

