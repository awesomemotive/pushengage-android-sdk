package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class UpdateTriggerStatusRequest {

    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("device_token_hash")
    @Expose
    private String deviceTokenHash;
    @SerializedName("triggerStatus")
    @Expose
    private long triggerStatus;

    /**
     * No args constructor for use in serialization
     */
    public UpdateTriggerStatusRequest() {
    }

    /**
     * @param triggerStatus
     * @param siteId
     * @param deviceTokenHash
     */
    public UpdateTriggerStatusRequest(Long siteId, String deviceTokenHash, long triggerStatus) {
        super();
        this.siteId = siteId;
        this.deviceTokenHash = deviceTokenHash;
        this.triggerStatus = triggerStatus;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getDeviceTokenHash() {
        return deviceTokenHash;
    }

    public void setDeviceTokenHash(String deviceTokenHash) {
        this.deviceTokenHash = deviceTokenHash;
    }

    public long getTriggerStatus() {
        return triggerStatus;
    }

    public void setTriggerStatus(long triggerStatus) {
        this.triggerStatus = triggerStatus;
    }

}