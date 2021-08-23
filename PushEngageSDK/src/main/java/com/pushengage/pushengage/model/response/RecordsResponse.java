package com.pushengage.pushengage.model.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class RecordsResponse {

    @SerializedName("SequenceNumber")
    @Expose
    private String sequenceNumber;
    @SerializedName("ShardId")
    @Expose
    private String shardId;

    /**
     * No args constructor for use in serialization
     */
    public RecordsResponse() {
    }

    /**
     * @param sequenceNumber
     * @param shardId
     */
    public RecordsResponse(String sequenceNumber, String shardId) {
        super();
        this.sequenceNumber = sequenceNumber;
        this.shardId = shardId;
    }

    public String getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(String sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getShardId() {
        return shardId;
    }

    public void setShardId(String shardId) {
        this.shardId = shardId;
    }

}
