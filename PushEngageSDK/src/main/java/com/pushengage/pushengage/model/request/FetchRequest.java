package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class FetchRequest {

    @SerializedName("tag")
    @Expose
    private String tag;
    @SerializedName("postback")
    @Expose
    private Object postback;

    /**
     * No args constructor for use in serialization
     */
    public FetchRequest() {
    }

    /**
     * @param postback
     * @param tag
     */
    public FetchRequest(String tag, Object postback) {
        super();
        this.tag = tag;
        this.postback = postback;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Object getPostback() {
        return postback;
    }

    public void setPostback(Object postback) {
        this.postback = postback;
    }

}
