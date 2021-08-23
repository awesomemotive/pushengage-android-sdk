package com.pushengage.pushengage.model.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ChannelResponse {
    public class Data {

        @SerializedName("channel_id")
        @Expose
        private long channelId;
        @SerializedName("group_id")
        @Expose
        private long groupId;
        @SerializedName("channel_name")
        @Expose
        private String channelName;
        @SerializedName("channel_description")
        @Expose
        private String channelDescription;
        @SerializedName("options")
        @Expose
        private Options options;
        @SerializedName("site_id")
        @Expose
        private long siteId;
        @SerializedName("group_name")
        @Expose
        private String groupName;

        /**
         * No args constructor for use in serialization
         */
        public Data() {
        }

        /**
         * @param channelDescription
         * @param groupName
         * @param groupId
         * @param options
         * @param siteId
         * @param channelName
         * @param channelId
         */
        public Data(long channelId, long groupId, String channelName, String channelDescription, Options options, long siteId, String groupName) {
            super();
            this.channelId = channelId;
            this.groupId = groupId;
            this.channelName = channelName;
            this.channelDescription = channelDescription;
            this.options = options;
            this.siteId = siteId;
            this.groupName = groupName;
        }

        public long getChannelId() {
            return channelId;
        }

        public void setChannelId(long channelId) {
            this.channelId = channelId;
        }

        public long getGroupId() {
            return groupId;
        }

        public void setGroupId(long groupId) {
            this.groupId = groupId;
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

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options;
        }

        public long getSiteId() {
            return siteId;
        }

        public void setSiteId(long siteId) {
            this.siteId = siteId;
        }

        public String getGroupName() {
            return groupName;
        }

        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

    }


    @SerializedName("error_code")
    @Expose
    private long errorCode;
    @SerializedName("data")
    @Expose
    private Data data;

    /**
     * No args constructor for use in serialization
     */
    public ChannelResponse() {
    }

    /**
     * @param data
     * @param errorCode
     */
    public ChannelResponse(long errorCode, Data data) {
        super();
        this.errorCode = errorCode;
        this.data = data;
    }

    public long getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(long errorCode) {
        this.errorCode = errorCode;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public class Options {

        @SerializedName("importance")
        @Expose
        private String importance;
        @SerializedName("sound")
        @Expose
        private String sound;
        @SerializedName("sound_file")
        @Expose
        private String soundFile;
        @SerializedName("vibration")
        @Expose
        private String vibration;
        @SerializedName("vibration_pattern")
        @Expose
        private List<Long> vibrationPattern = null;
        @SerializedName("led_color")
        @Expose
        private String ledColor;
        @SerializedName("led_color_code")
        @Expose
        private String ledColorCode;
        @SerializedName("accent_color")
        @Expose
        private String accentColor;
        @SerializedName("badges")
        @Expose
        private boolean badges;
        @SerializedName("lock_screen")
        @Expose
        private String lockScreen;

        /**
         * No args constructor for use in serialization
         */
        public Options() {
        }

        /**
         * @param badges
         * @param ledColor
         * @param importance
         * @param accentColor
         * @param lockScreen
         * @param sound
         * @param vibrationPattern
         * @param soundFile
         * @param vibration
         * @param ledColorCode
         */
        public Options(String importance, String sound, String soundFile, String vibration, List<Long> vibrationPattern,
                       String ledColor, String ledColorCode, String accentColor, Boolean badges, String lockScreen) {
            super();
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

        public List<Long> getVibrationPattern() {
            return vibrationPattern;
        }

        public void setVibrationPattern(List<Long> vibrationPattern) {
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
}