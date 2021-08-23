package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class AddDynamicSegmentRequest {

    @SerializedName("device_token_hash")
    @Expose
    private String deviceTokenHash;
    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("device_type")
    @Expose
    private String deviceType;
    @SerializedName("segments")
    @Expose
    private List<Segment> segments = null;

    /**
     * No args constructor for use in serialization
     */
    public AddDynamicSegmentRequest() {
    }

    /**
     * @param deviceType
     * @param siteId
     * @param deviceTokenHash
     * @param segments
     */
    public AddDynamicSegmentRequest(String deviceTokenHash, Long siteId, String deviceType, List<Segment> segments) {
        super();
        this.deviceTokenHash = deviceTokenHash;
        this.siteId = siteId;
        this.deviceType = deviceType;
        this.segments = segments;
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

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public void setSegments(List<Segment> segments) {
        this.segments = segments;
    }

    public class Segment {

        @SerializedName("name")
        @Expose
        private String name;
        @SerializedName("duration")
        @Expose
        private long duration;

        /**
         * No args constructor for use in serialization
         */
        public Segment() {
        }

        /**
         * @param duration
         * @param name
         */
        public Segment(String name, long duration) {
            super();
            this.name = name;
            this.duration = duration;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getDuration() {
            return duration;
        }

        public void setDuration(long duration) {
            this.duration = duration;
        }

    }

}