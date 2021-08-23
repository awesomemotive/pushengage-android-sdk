package com.pushengage.pushengage.model.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class FetchResponse {

    @SerializedName("error_code")
    @Expose
    private Long errorCode;
    @SerializedName("data")
    @Expose
    private Object data;

    /**
     * No args constructor for use in serialization
     */
    public FetchResponse() {
    }

    /**
     * @param data
     * @param errorCode
     */
    public FetchResponse(Long errorCode, Object data) {
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

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

}

