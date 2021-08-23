package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class UpdateSubscriberStatusRequest {

    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("device_token_hash")
    @Expose
    private String deviceTokenHash;
    @SerializedName("IsUnSubscribed")
    @Expose
    private long isUnSubscribed;
    @SerializedName("delete_on_notification_disable")
    @Expose
    private Boolean deleteOnNotificationDisable;

    /**
     * No args constructor for use in serialization
     *
     */
    public UpdateSubscriberStatusRequest() {
    }

    /**
     *
     * @param siteId
     * @param isUnSubscribed
     * @param deviceTokenHash
     */
    public UpdateSubscriberStatusRequest(Long siteId, String deviceTokenHash, long isUnSubscribed, Boolean deleteOnNotificationDisable) {
        super();
        this.siteId = siteId;
        this.deviceTokenHash = deviceTokenHash;
        this.isUnSubscribed = isUnSubscribed;
        this.deleteOnNotificationDisable = deleteOnNotificationDisable;
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

    public long getIsUnSubscribed() {
        return isUnSubscribed;
    }

    public void setIsUnSubscribed(long isUnSubscribed) {
        this.isUnSubscribed = isUnSubscribed;
    }

    public Boolean getDeleteOnNotificationDisable() {
        return deleteOnNotificationDisable;
    }

    public void setDeleteOnNotificationDisable(Boolean deleteOnNotificationDisable) {
        this.deleteOnNotificationDisable = deleteOnNotificationDisable;
    }

}