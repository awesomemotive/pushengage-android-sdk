package com.pushengage.pushengage.model.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class GenricResponse {

    @SerializedName("error_code")
    @Expose
    private Long errorCode;
    @SerializedName("data")
    @Expose
    private Object data;
    @SerializedName("error_message")
    @Expose
    private String errorMessage;
    @SerializedName("error")
    @Expose
    private Error error;

    /**
     * No args constructor for use in serialization
     */
    public GenricResponse() {
    }

    /**
     * @param data
     * @param errorMessage
     * @param errorCode
     * @param error
     */
    public GenricResponse(Long errorCode, Object data, String errorMessage, Error error) {
        super();
        this.errorCode = errorCode;
        this.data = data;
        this.errorMessage = errorMessage;
        this.error = error;
    }

    public Long getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Long errorCode) {
        this.errorCode = errorCode;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Error getError() {
        return error;
    }

    public void setError(Error error) {
        this.error = error;
    }

    public class Error {

        @SerializedName("message")
        @Expose
        private String message;
        @SerializedName("code")
        @Expose
        private Integer code;

        /**
         * No args constructor for use in serialization
         */
        public Error() {
        }

        /**
         * @param code
         * @param message
         */
        public Error(String message, Integer code) {
            super();
            this.message = message;
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

    }

}