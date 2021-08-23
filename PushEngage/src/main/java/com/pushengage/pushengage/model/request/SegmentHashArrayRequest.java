package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class SegmentHashArrayRequest {

    @SerializedName("device_token_hash")
    @Expose
    private String deviceTokenHash;
    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("segment_id")
    @Expose
    private String segmentId;

    /**
     * No args constructor for use in serialization
     */
    public SegmentHashArrayRequest() {
    }

    /**
     * @param segmentId
     * @param siteId
     * @param deviceTokenHash
     */
    public SegmentHashArrayRequest(String deviceTokenHash, Long siteId, String segmentId) {
        super();
        this.deviceTokenHash = deviceTokenHash;
        this.siteId = siteId;
        this.segmentId = segmentId;
    }

    public String getDeviceTokenHash() {
        return deviceTokenHash;
    }

    public void setDeviceTokenHash(String deviceTokenHash) {
        this.deviceTokenHash = deviceTokenHash;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

}
