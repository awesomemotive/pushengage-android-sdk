package com.pushengage.pushengage.model.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class AddSubscriberResponse {

    @SerializedName("error_code")
    @Expose
    private Long errorCode;
    @SerializedName("data")
    @Expose
    private Data data;

    /**
     * No args constructor for use in serialization
     */
    public AddSubscriberResponse() {
    }

    /**
     * @param data
     * @param errorCode
     */
    public AddSubscriberResponse(Long errorCode, Data data) {
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

    public class Data {

        @SerializedName("subscriber_hash")
        @Expose
        private String subscriberHash;

        /**
         * No args constructor for use in serialization
         */
        public Data() {
        }

        /**
         * @param subscriberHash
         */
        public Data(String subscriberHash) {
            super();
            this.subscriberHash = subscriberHash;
        }

        public String getSubscriberHash() {
            return subscriberHash;
        }

        public void setSubscriberHash(String subscriberHash) {
            this.subscriberHash = subscriberHash;
        }

    }

}
