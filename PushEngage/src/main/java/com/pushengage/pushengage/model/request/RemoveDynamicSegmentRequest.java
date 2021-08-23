package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class RemoveDynamicSegmentRequest {

    @SerializedName("device_token")
    @Expose
    private String deviceToken;
    @SerializedName("site_id")
    @Expose
    private String siteId;
    @SerializedName("segment")
    @Expose
    private List<String> segment = null;
    @SerializedName("device_type")
    @Expose
    private String deviceType;

    /**
     * No args constructor for use in serialization
     */
    public RemoveDynamicSegmentRequest() {
    }

    /**
     * @param deviceType
     * @param segment
     * @param siteId
     * @param deviceToken
     */
    public RemoveDynamicSegmentRequest(String deviceToken, String siteId, List<String> segment, String deviceType) {
        super();
        this.deviceToken = deviceToken;
        this.siteId = siteId;
        this.segment = segment;
        this.deviceType = deviceType;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }

    public List<String> getSegment() {
        return segment;
    }

    public void setSegment(List<String> segment) {
        this.segment = segment;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

}