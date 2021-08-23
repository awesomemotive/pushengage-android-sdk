package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class AddSegmentRequest {
    @SerializedName("device_token_hash")
    @Expose
    private String deviceTokenHash;
    @SerializedName("segment")
    @Expose
    private List<String> segment = null;
    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("device_type")
    @Expose
    private String deviceType;

    /**
     * No args constructor for use in serialization
     *
     */
    public AddSegmentRequest() {
    }

    /**
     *
     * @param deviceType
     * @param segment
     * @param siteId
     * @param deviceTokenHash
     */
    public AddSegmentRequest(String deviceTokenHash, List<String> segment, Long siteId, String deviceType) {
        super();
        this.deviceTokenHash = deviceTokenHash;
        this.segment = segment;
        this.siteId = siteId;
        this.deviceType = deviceType;
    }

    public String getDeviceTokenHash() {
        return deviceTokenHash;
    }

    public void setDeviceTokenHash(String deviceTokenHash) {
        this.deviceTokenHash = deviceTokenHash;
    }

    public List<String> getSegment() {
        return segment;
    }

    public void setSegment(List<String> segment) {
        this.segment = segment;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

}