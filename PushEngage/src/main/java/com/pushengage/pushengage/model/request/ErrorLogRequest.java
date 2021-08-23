package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class ErrorLogRequest {

    public class Data {

        @SerializedName("tag")
        @Expose
        private String tag;
        @SerializedName("device_token_hash")
        @Expose
        private String deviceTokenHash;
        @SerializedName("device")
        @Expose
        private String device;
        @SerializedName("timezone")
        @Expose
        private String timezone;
        @SerializedName("error")
        @Expose
        private String error;

        /**
         * No args constructor for use in serialization
         */
        public Data() {
        }

        /**
         * @param timezone
         * @param tag
         * @param error
         * @param device
         * @param deviceTokenHash
         */
        public Data(String tag, String deviceTokenHash, String device, String timezone, String error) {
            super();
            this.tag = tag;
            this.deviceTokenHash = deviceTokenHash;
            this.device = device;
            this.timezone = timezone;
            this.error = error;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public String getDeviceTokenHash() {
            return deviceTokenHash;
        }

        public void setDeviceTokenHash(String deviceTokenHash) {
            this.deviceTokenHash = deviceTokenHash;
        }

        public String getDevice() {
            return device;
        }

        public void setDevice(String device) {
            this.device = device;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

    }

    @SerializedName("app")
    @Expose
    private String app;
    @SerializedName("name")
    @Expose
    private String name;
    @SerializedName("data")
    @Expose
    private Data data;

    /**
     * No args constructor for use in serialization
     */
    public ErrorLogRequest() {
    }

    /**
     * @param app
     * @param data
     * @param name
     */
    public ErrorLogRequest(String app, String name, Data data) {
        super();
        this.app = app;
        this.name = name;
        this.data = data;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

}