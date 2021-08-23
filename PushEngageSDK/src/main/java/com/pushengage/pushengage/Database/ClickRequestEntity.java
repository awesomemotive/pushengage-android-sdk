package com.pushengage.pushengage.Database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "ClickRequest")
public class ClickRequestEntity {

    @NonNull
    public Long getId() {
        return id;
    }

    public void setId(@NonNull Long id) {
        this.id = id;
    }

    @NonNull
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private Long id;

    @NonNull
    @ColumnInfo(name = "deviceHash")
    private String deviceHash;

    @NonNull
    @ColumnInfo(name = "tag")
    private String tag;

    @NonNull
    @ColumnInfo(name = "action")
    private String action;

    @NonNull
    @ColumnInfo(name = "device_type")
    private String deviceType;


    @NonNull
    @ColumnInfo(name = "device")
    private String device;


    @NonNull
    @ColumnInfo(name = "swv")
    private String swv;

    @NonNull
    @ColumnInfo(name = "timezone")
    private String timezone;

    @NonNull
    public String getDeviceHash() {
        return deviceHash;
    }

    public void setDeviceHash(@NonNull String deviceHash) {
        this.deviceHash = deviceHash;
    }

    @NonNull
    public String getTag() {
        return tag;
    }

    public void setTag(@NonNull String tag) {
        this.tag = tag;
    }

    @NonNull
    public String getAction() {
        return action;
    }

    public void setAction(@NonNull String action) {
        this.action = action;
    }

    @NonNull
    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(@NonNull String deviceType) {
        this.deviceType = deviceType;
    }

    @NonNull
    public String getDevice() {
        return device;
    }

    public void setDevice(@NonNull String device) {
        this.device = device;
    }

    @NonNull
    public String getSwv() {
        return swv;
    }

    public void setSwv(@NonNull String swv) {
        this.swv = swv;
    }

    @NonNull
    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(@NonNull String timezone) {
        this.timezone = timezone;
    }

    public ClickRequestEntity(String deviceHash, String tag, String action, String deviceType, String device, String swv, String timezone) {
        this.deviceHash = deviceHash;
        this.tag = tag;
        this.action = action;
        this.deviceType = deviceType;
        this.device = device;
        this.swv = swv;
        this.timezone = timezone;
    }
}
