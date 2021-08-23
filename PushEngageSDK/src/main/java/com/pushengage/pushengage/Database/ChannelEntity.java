package com.pushengage.pushengage.Database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

@Entity(tableName = "Channel")
public class ChannelEntity {

    @ColumnInfo(name = "channel_id")
    @NonNull
    @PrimaryKey()
    private String channelId;
    @ColumnInfo(name = "channel_name")
    private String channelName;
    @ColumnInfo(name = "channel_description")
    private String channelDescription;
    @ColumnInfo(name = "group_id")
    private String groupId;
    @ColumnInfo(name = "group_name")
    private String groupName;
    @ColumnInfo(name = "importance")
    private String importance;
    @ColumnInfo(name = "sound")
    private String sound;
    @ColumnInfo(name = "sound_file")
    private String soundFile;
    @ColumnInfo(name = "vibration")
    private String vibration;
    @ColumnInfo(name = "vibration_pattern")
    private String vibrationPattern = null;
    @ColumnInfo(name = "led_color")
    private String ledColor;
    @ColumnInfo(name = "led_color_code")
    private String ledColorCode;
    @ColumnInfo(name = "accent_color")
    private String accentColor;
    @ColumnInfo(name = "badges")
    private Boolean badges;
    @ColumnInfo(name = "lock_screen")
    private String lockScreen;

    /**
     * No args constructor for use in serialization
     */
    public ChannelEntity() {
    }

    /**
     * @param channelDescription
     * @param groupName
     * @param groupId
     * @param channelName
     * @param channelId
     * @param badges
     * @param ledColor
     * @param importance
     * @param lockScreen
     * @param sound
     * @param vibrationPattern
     * @param soundFile
     * @param vibration
     * @param ledColorCode
     */
    public ChannelEntity(String channelId, String channelName, String channelDescription, String groupId, String groupName, String importance, String sound,
                         String soundFile, String vibration, String vibrationPattern, String ledColor, String ledColorCode, String accentColor, Boolean badges,
                         String lockScreen) {
        super();
        this.channelId = channelId;
        this.channelName = channelName;
        this.channelDescription = channelDescription;
        this.groupId = groupId;
        this.groupName = groupName;
        this.importance = importance;
        this.sound = sound;
        this.soundFile = soundFile;
        this.vibration = vibration;
        this.vibrationPattern = vibrationPattern;
        this.ledColor = ledColor;
        this.ledColorCode = ledColorCode;
        this.accentColor = accentColor;
        this.badges = badges;
        this.lockScreen = lockScreen;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getChannelDescription() {
        return channelDescription;
    }

    public void setChannelDescription(String channelDescription) {
        this.channelDescription = channelDescription;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getImportance() {
        return importance;
    }

    public void setImportance(String importance) {
        this.importance = importance;
    }

    public String getSound() {
        return sound;
    }

    public void setSound(String sound) {
        this.sound = sound;
    }

    public String getSoundFile() {
        return soundFile;
    }

    public void setSoundFile(String soundFile) {
        this.soundFile = soundFile;
    }

    public String getVibration() {
        return vibration;
    }

    public void setVibration(String vibration) {
        this.vibration = vibration;
    }

    public String getVibrationPattern() {
        return vibrationPattern;
    }

    public void setVibrationPattern(String vibrationPattern) {
        this.vibrationPattern = vibrationPattern;
    }

    public String getLedColor() {
        return ledColor;
    }

    public void setLedColor(String ledColor) {
        this.ledColor = ledColor;
    }

    public String getLedColorCode() {
        return ledColorCode;
    }

    public void setLedColorCode(String ledColorCode) {
        this.ledColorCode = ledColorCode;
    }

    public String getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(String accentColor) {
        this.accentColor = accentColor;
    }

    public Boolean getBadges() {
        return badges;
    }

    public void setBadges(Boolean badges) {
        this.badges = badges;
    }

    public String getLockScreen() {
        return lockScreen;
    }

    public void setLockScreen(String lockScreen) {
        this.lockScreen = lockScreen;
    }

}
