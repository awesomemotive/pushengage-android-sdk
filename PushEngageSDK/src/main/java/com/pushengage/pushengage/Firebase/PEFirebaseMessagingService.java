package com.pushengage.pushengage.Firebase;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.gson.Gson;
import com.pushengage.pushengage.Database.ChannelEntity;
import com.pushengage.pushengage.Database.DaoInterface;
import com.pushengage.pushengage.Database.PERoomDatabase;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.R;
import com.pushengage.pushengage.Service.NotificationService;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.payload.PayloadPOJO;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.request.FetchRequest;
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest;
import com.pushengage.pushengage.model.request.UpgradeSubscriberRequest;
import com.pushengage.pushengage.model.response.ChannelResponse;
import com.pushengage.pushengage.model.response.FetchResponse;
import com.pushengage.pushengage.model.response.GenricResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PEFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseMsgService";
    private HashMap<String, String> additionalData;
    private PEPrefs prefs;
    private Gson gson = new Gson();
    private PERoomDatabase peRoomDatabase;
    private DaoInterface daoInterface;

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    // [START receive_message]
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "From: " + remoteMessage.getFrom());
        prefs = new PEPrefs(this);
        prefs.setPayload(String.valueOf(remoteMessage.getData()));

        // Check if message contains a data payload.
        if (remoteMessage.getData().size() > 0) {
            Log.d(TAG, "Message data payload: " + remoteMessage.getData());
        }

        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();

        long longVal = areNotificationsEnabled ? 0 : 1;
        if (!(longVal == prefs.isNotificationDisabled())) {
            if (areNotificationsEnabled && prefs.isSubscriberDeleted()) {
                PushEngage.callAddSubscriberAPI();
            } else {
                UpdateSubscriberStatusRequest updateSubscriberStatusRequest = new UpdateSubscriberStatusRequest(prefs.getSiteId(), prefs.getHash(), longVal, prefs.getDeleteOnNotificationDisable());
                updateSubscriberStatus(updateSubscriberStatusRequest);
            }
        }

        Map<String, String> params = remoteMessage.getData();
        JSONObject object = new JSONObject(params);
        PayloadPOJO payloadPOJO = gson.fromJson(object.toString(), PayloadPOJO.class);

        additionalData = new Gson().fromJson(payloadPOJO.getAd(), HashMap.class);

        if (!TextUtils.isEmpty(payloadPOJO.getRf()) && payloadPOJO.getRf().equalsIgnoreCase("1")) {
            FetchRequest fetchRequest = new FetchRequest(payloadPOJO.getTag(), payloadPOJO.getPb());
            callFetch(fetchRequest, payloadPOJO.getCi(), payloadPOJO.getId(), false);
        } else {
            if (areNotificationsEnabled) {
                notificationView(payloadPOJO.getTag(), false);
            }
            sendNotification(payloadPOJO, false);
        }

    }

    /**
     * Called if FCM registration token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the
     * FCM registration token is initially generated so this is where you would retrieve
     * the token.
     */
    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "Refreshed token: " + token);
        upgradeToken(token);
    }

    /**
     * Create and show a simple notification containing the received FCM message.
     *
     * @param payload FCM payload received
     */
    private void sendNotification(PayloadPOJO payload, Boolean isSponsored) {

        String ci = payload.getCi();
        if (TextUtils.isEmpty(ci)) {
            ci = PEConstants.DEFAULT_CHANNEL_ID;
        }
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, ci);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder.setAutoCancel(true);

//      Handling notification click
        Intent clickIntent = new Intent(this, NotificationService.class);
        clickIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        if (!TextUtils.isEmpty(payload.getU())){
            clickIntent.putExtra("url", payload.getU());
        } else if (!TextUtils.isEmpty(payload.getCu())) {
            clickIntent.putExtra("url", payload.getCu());
        }
        if (!TextUtils.isEmpty(payload.getTag()))
            clickIntent.putExtra("tag", payload.getTag());
        clickIntent.putExtra("data", additionalData);
        clickIntent.putExtra("id", payload.getId());
        PendingIntent clickPendingIntent = PendingIntent.getService(getApplicationContext(), payload.getId(), clickIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        notificationBuilder.setContentIntent(clickPendingIntent);

//          Set Action Buttons
        if (!TextUtils.isEmpty(payload.getAb())) {
            JSONArray jsonArray = null;
            try {
                jsonArray = new JSONArray(payload.getAb());
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject object = jsonArray.getJSONObject(i);
                    PayloadPOJO.Ab ab = gson.fromJson(object.toString(), PayloadPOJO.Ab.class);

                    Intent actionButton = new Intent(this, NotificationService.class);
                    actionButton.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    if (!TextUtils.isEmpty(ab.getU())) {
                        actionButton.putExtra("url", ab.getU());
                    } else if (!TextUtils.isEmpty(payload.getCu())) {
                        actionButton.putExtra("url", payload.getCu());
                    }
                    actionButton.putExtra("data", additionalData);
                    actionButton.putExtra("tag", payload.getTag());
                    actionButton.putExtra("action", "action" + (i + 1));
                    actionButton.putExtra("id", payload.getId());
                    PendingIntent actionButtonsPendingIntent = PendingIntent.getService(getApplicationContext(), 1200 + i, actionButton, PendingIntent.FLAG_UPDATE_CURRENT);
                    Resources resources = getApplicationContext().getResources();
                    int iconResourceId = 0;
                    try {
                        iconResourceId = resources.getIdentifier(ab.getI(), "drawable",
                                getApplicationContext().getPackageName());
                    } catch (Exception e) {
                        iconResourceId = 0;
                    }
                    notificationBuilder.addAction(iconResourceId, ab.getL(), actionButtonsPendingIntent);
                }
            } catch (JSONException e) {
//                e.printStackTrace();
            }
        }

//      Setting notification Title.
        if (!TextUtils.isEmpty(payload.getT()))
            notificationBuilder.setContentTitle(payload.getT());

//      Setting notification Body.
        if (!TextUtils.isEmpty(payload.getB()))
            notificationBuilder.setContentText(payload.getB());

//      Setting notification Small Icon.
        if (!TextUtils.isEmpty(payload.getSi())) {
            try {
                Resources resources = getApplicationContext().getResources();
                final int smallIconResourceId = resources.getIdentifier(payload.getSi(), "drawable",
                        getApplicationContext().getPackageName());
                notificationBuilder.setSmallIcon(smallIconResourceId);
                notificationBuilder.setSmallIcon(smallIconResourceId);
            } catch (Exception e) {
                notificationBuilder.setSmallIcon(R.drawable.ic_stat_notification_default);
                notificationBuilder.setSmallIcon(R.drawable.ic_stat_notification_default);
//                e.printStackTrace();
            }
        } else {
            try {
                Resources resources = getApplicationContext().getResources();
                final int smallIconResourceId = resources.getIdentifier(prefs.getSmallIconResource(), "drawable",
                        getApplicationContext().getPackageName());
                notificationBuilder.setSmallIcon(smallIconResourceId);
                notificationBuilder.setSmallIcon(smallIconResourceId);
            } catch (Exception e) {
                notificationBuilder.setSmallIcon(R.drawable.ic_stat_notification_default);
                notificationBuilder.setSmallIcon(R.drawable.ic_stat_notification_default);
//                e.printStackTrace();
            }
        }

//      Setting notification Accent Color.
        if (!TextUtils.isEmpty(payload.getAc()))
            notificationBuilder.setColor(Color.parseColor("#" + payload.getAc()));

//      Set Notification Priority.
        try {
            switch (payload.getP()) {
                case "PRIORITY_MAX":
                    notificationBuilder.setPriority(Notification.PRIORITY_MAX);
                    notificationBuilder.setPriority(Notification.PRIORITY_MAX);
                    break;
                case "PRIORITY_MIN":
                    notificationBuilder.setPriority(Notification.PRIORITY_MIN);
                    notificationBuilder.setPriority(Notification.PRIORITY_MIN);
                    break;
                default:
                    notificationBuilder.setPriority(Notification.PRIORITY_DEFAULT);
                    break;
            }
        } catch (Exception e) {
            notificationBuilder.setPriority(Notification.PRIORITY_DEFAULT);
        }

//      Set Group key
        try {
            if (!TextUtils.isEmpty(payload.getGk())) {
                notificationBuilder.setGroup(payload.getGk());
            }

        } catch (Exception e) {
//            e.printStackTrace();
        }

//      Method to Create channel
        try {
            setChannelInfo(ci, notificationManager, notificationBuilder, payload, false);
        } catch (Exception e) {
//            e.printStackTrace();
        }

    }

    /**
     * Download and set Notification Images.
     *
     * @param payload
     * @param notificationBuilder
     * @param notificationManager
     */
    private void setNotificationImages(PayloadPOJO payload, NotificationCompat.Builder notificationBuilder, NotificationManager notificationManager) {
//      Setting notification Common Image.
        if (!TextUtils.isEmpty(payload.getIm())) {
            try {

                Glide.with(this)
                        .asBitmap()
                        .load(payload.getIm())
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                notificationBuilder.setLargeIcon(resource);
                                notificationBuilder.setStyle(new NotificationCompat.BigPictureStyle()
                                        .bigPicture(resource)
                                        .bigLargeIcon(null));
                                notificationManager.notify(payload.getId(), notificationBuilder.build());
                            }

                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {
                            }
                        });

            } catch (Exception e) {
//                Log.d(TAG, "Payload Image Exception");
            }
        }
//      Setting notification Large Icon.
        if (!TextUtils.isEmpty(payload.getLi())) {
            try {
                Glide.with(this)
                        .asBitmap()
                        .load(payload.getLi())
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                notificationBuilder.setLargeIcon(resource);
                                notificationManager.notify(payload.getId(), notificationBuilder.build());
                            }

                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {
                            }
                        });

            } catch (Exception e) {
//                Log.d(TAG, "Payload Large Image Exception");
            }
        }

//      Setting notification Big Picture.
        if (!TextUtils.isEmpty(payload.getBp())) {
            try {
                Glide.with(this)
                        .asBitmap()
                        .load(payload.getBp())
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                notificationBuilder.setStyle(new NotificationCompat.BigPictureStyle()
                                        .bigPicture(resource)
                                        .bigLargeIcon(null));
                                notificationManager.notify(payload.getId(), notificationBuilder.build());
                            }

                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {
                            }
                        });
            } catch (Exception e) {
//                e.printStackTrace();
            }
        }

    }

    /**
     * Publish the created notification
     *
     * @param payload
     * @param notificationBuilder
     * @param notificationManager
     */
    private void publishNotification(PayloadPOJO payload, NotificationCompat.Builder notificationBuilder, NotificationManager notificationManager) {
        setNotificationImages(payload, notificationBuilder, notificationManager);
        notificationManager.notify(payload.getId(), notificationBuilder.build());
    }

    /**
     * Configuring Channel settings.
     */
    private void setChannelInfo(String channelId, NotificationManager notificationManager, NotificationCompat.Builder notificationBuilder, PayloadPOJO payload, Boolean isDefault) {
        peRoomDatabase = PERoomDatabase.getDatabase(getApplicationContext());
        daoInterface = peRoomDatabase.daoInterface();
        ChannelEntity channelEntity = daoInterface.getChannel(channelId);
        if (channelEntity == null) {
            if (isDefault) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(PEConstants.DEFAULT_CHANNEL_ID, PEConstants.DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
                    notificationManager.createNotificationChannel(channel);
                }
//              Publish Notification
                publishNotification(payload, notificationBuilder, notificationManager);
            } else {

                getChannelInfo(channelId, notificationManager, notificationBuilder, payload);
            }
        } else {
//          Set Lockscreen visiblity
            int visibility;
            switch (channelEntity.getLockScreen()) {
                case "VISIBILITY_PRIVATE":
                    visibility = NotificationCompat.VISIBILITY_PRIVATE;
                    notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
                    notificationBuilder.setPublicVersion(notificationBuilder.build());
                    break;
                case "VISIBILITY_PUBLIC":
                    visibility = NotificationCompat.VISIBILITY_PUBLIC;
                    break;
                case "VISIBILITY_SECRET":
                    visibility = NotificationCompat.VISIBILITY_SECRET;
                    break;
                default:
                    visibility = NotificationCompat.VISIBILITY_PUBLIC;
                    break;
            }
            notificationBuilder.setVisibility(visibility);

//          Set Led Light color for Devices older than Android O
            try {
                if (channelEntity.getLedColor().equalsIgnoreCase("OFF")) {
                    notificationBuilder.setLights(0, 0, 0);
                } else if (channelEntity.getLedColor().equalsIgnoreCase("CUSTOM")) {
                    notificationBuilder.setLights(Color.parseColor("#" + channelEntity.getLedColorCode()), 1000, 1000);
                }
            } catch (Exception e) {

            }

//          Set channel Importance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int importance;
                switch (channelEntity.getImportance()) {
                    case "IMPORTANCE_HIGH":
                        importance = NotificationManager.IMPORTANCE_HIGH;
                        break;
                    case "IMPORTANCE_DEFAULT":
                        importance = NotificationManager.IMPORTANCE_DEFAULT;
                        break;
                    case "IMPORTANCE_LOW":
                        importance = NotificationManager.IMPORTANCE_LOW;
                        break;
                    case "IMPORTANCE_MIN":
                        importance = NotificationManager.IMPORTANCE_MIN;
                        break;
                    default:
                        importance = NotificationManager.IMPORTANCE_DEFAULT;
                        break;
                }

//              Register the channel with the system; you can't change the importance or other notification behaviors after this
                if (!TextUtils.isEmpty(channelId) && !TextUtils.isEmpty(channelEntity.getChannelName())) {
                    NotificationChannel channel = new NotificationChannel(channelId, channelEntity.getChannelName(), importance);
                    if (!TextUtils.isEmpty(channelEntity.getChannelDescription()))
                        channel.setDescription(channelEntity.getChannelDescription());
                    channel = setChannelSound(channel, channelEntity.getSound(), channelEntity.getSoundFile());
                    channel = setChannelVibration(channel, channelEntity.getVibration(), channelEntity.getVibrationPattern());
                    channel = setChannelLedColor(channel, channelEntity.getLedColor(), channelEntity.getLedColorCode());
                    channel.setLockscreenVisibility(visibility);
                    if (channelEntity.getBadges() != null) {
                        channel.setShowBadge(channelEntity.getBadges());
                    }
                    if (!TextUtils.isEmpty(channelEntity.getGroupId()) && !TextUtils.isEmpty(channelEntity.getGroupName())) {
                        notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(channelEntity.getGroupId(), channelEntity.getGroupName()));
                        channel.setGroup(channelEntity.getGroupId());
                    }
                    notificationManager.createNotificationChannel(channel);
                }

            } else {
//              Setting notification sound for Devices older than Android O.
                try {
                    if (TextUtils.isEmpty(channelEntity.getSound()) || channelEntity.getSound().equalsIgnoreCase("DEFAULT")) {
                        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                        notificationBuilder.setSound(defaultSoundUri);
                    } else if (channelEntity.getSound().equalsIgnoreCase("OFF")) {
                        notificationBuilder.setSound(null);
                    } else if (channelEntity.getSound().equalsIgnoreCase("CUSTOM")) {
                        Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/" + channelEntity.getSoundFile());
                        notificationBuilder.setSound(soundUri);
                    }
                } catch (Exception e) {
//                    e.printStackTrace();
                }

//              Setting notification vibration for Devices older than Android O.
                try {
                    if (TextUtils.isEmpty(channelEntity.getVibration()) || channelEntity.getVibration().equalsIgnoreCase("DEFAULT")) {
                        Notification notification = new Notification();
                        notification.defaults |= Notification.DEFAULT_VIBRATE;
                        notificationBuilder.setDefaults(notification.defaults);
                    } else if (channelEntity.getVibration().equalsIgnoreCase("OFF")) {
                        notificationBuilder.setVibrate(null);
                    } else if (channelEntity.getVibration().equalsIgnoreCase("CUSTOM")) {
                        JSONArray jsonArray = new JSONArray(channelEntity.getVibrationPattern());
                        long array[] = new long[jsonArray.length()];
                        for (int i = 0; i < jsonArray.length(); i++) {
                            array[i] = jsonArray.getLong(i);
                        }
                        notificationBuilder.setVibrate(array);
                    }
                } catch (Exception e) {
//                    e.printStackTrace();
                }
            }
//          Publish Notification
            publishNotification(payload, notificationBuilder, notificationManager);
        }
    }

    /**
     * API call to fetch sponsored notifications
     *
     * @param fetchRequest
     * @param channelId
     * @param id
     * @param isRetry
     */
    private void callFetch(FetchRequest fetchRequest, String channelId, Integer id, boolean isRetry) {
        Call<FetchResponse> addRecordsResponseCall = RestClient.getBackendClient(getApplicationContext()).fetch(fetchRequest);
        addRecordsResponseCall.enqueue(new Callback<FetchResponse>() {
            @Override
            public void onResponse(@NonNull Call<FetchResponse> call, @NonNull Response<FetchResponse> response) {
                if (response.isSuccessful()) {
                    FetchResponse fetchResponse = response.body();
                    String json = gson.toJson(fetchResponse.getData()); // serializes target to Json
                    PayloadPOJO payloadPOJO = gson.fromJson(json, PayloadPOJO.class);
                    payloadPOJO.setCi(channelId);
                    payloadPOJO.setId(id);
                    NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
                    if (notificationManagerCompat.areNotificationsEnabled()) {
                        notificationView(payloadPOJO.getTag(), false);
                    }
                    sendNotification(payloadPOJO, true);
                } else if (response.code() != 404) {
//                    Log.d(TAG, "API Failure");
                    if (!isRetry) {
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                callFetch(fetchRequest, channelId, id, true);
                            }
                        }, PEConstants.RETRY_DELAY);
                    } else {
                        String jsonStr = gson.toJson(response.body());
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        ErrorLogRequest.Data data = errorLogRequest.new Data(fetchRequest.getTag(), prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                        errorLogRequest.setName(PEConstants.NOTIFICATION_REFETCH_FAILED);
                        errorLogRequest.setData(data);
                        PEUtilities.addLogs(getApplicationContext(), TAG, errorLogRequest);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<FetchResponse> call, @NonNull Throwable t) {
//                Log.d(TAG, "API Failure");
                if (!isRetry) {
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            callFetch(fetchRequest, channelId, id, true);
                        }
                    }, PEConstants.RETRY_DELAY);
                } else {
                    ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                    ErrorLogRequest.Data data = errorLogRequest.new Data(fetchRequest.getTag(), prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                    errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                    errorLogRequest.setName(PEConstants.NOTIFICATION_REFETCH_FAILED);
                    errorLogRequest.setData(data);
                    PEUtilities.addLogs(getApplicationContext(), TAG, errorLogRequest);
                }
            }
        });
    }

    /**
     * Set Notification Sound for Channel
     *
     * @param channel
     * @param sound
     * @param soundFile
     * @return
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private NotificationChannel setChannelSound(NotificationChannel channel, String sound, String soundFile) {
        if (TextUtils.isEmpty(sound) || sound.equalsIgnoreCase("DEFAULT")) {
            Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes att = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            channel.setSound(defaultSoundUri, att);
        } else if (sound.equalsIgnoreCase("OFF")) {
            channel.setSound(null, null);
        } else if (sound.equalsIgnoreCase("CUSTOM")) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/" + soundFile);
            channel.setSound(soundUri, audioAttributes);
        }
        return channel;
    }

    /**
     * Set Channel Vibration pattern
     *
     * @param channel
     * @param vibration
     * @param vibrationPattern
     * @return
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private NotificationChannel setChannelVibration(NotificationChannel channel, String vibration, String vibrationPattern) {
        try {
            if (TextUtils.isEmpty(vibration) || vibration.equalsIgnoreCase("DEFAULT")) {
                channel.enableVibration(true);
            } else if (vibration.equalsIgnoreCase("OFF")) {
                channel.enableVibration(false);
            } else if (vibration.equalsIgnoreCase("CUSTOM")) {
                channel.enableVibration(true);
                JSONArray jsonArray = new JSONArray(vibrationPattern);
                long array[] = new long[jsonArray.length()];
                for (int i = 0; i < jsonArray.length(); i++) {
                    array[i] = jsonArray.getLong(i);
                }
                channel.setVibrationPattern(array);
            }
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return channel;
    }

    /**
     * Set Led Color for Channel
     *
     * @param channel
     * @param led
     * @param colorCode
     * @return
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private NotificationChannel setChannelLedColor(NotificationChannel channel, String led, String colorCode) {
        try {
            if (TextUtils.isEmpty(led) || led.equalsIgnoreCase("DEFAULT")) {
                channel.enableLights(true);
            } else if (led.equalsIgnoreCase("OFF")) {
                channel.enableLights(false);
            } else if (led.equalsIgnoreCase("CUSTOM")) {
                channel.enableLights(true);
                channel.setLightColor(Color.parseColor("#" + colorCode));
            }
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return channel;
    }

    /**
     * API call tacking Notification Views(analytics
     *
     * @param tag
     * @param isRetry
     */
    public void notificationView(String tag, boolean isRetry) {
        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("referer", "https://pushengage.com/service-worker.js");
        String device = "";
        TelephonyManager manager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (Objects.requireNonNull(manager).getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
            device = PEConstants.TABLET;
        } else {
            device = PEConstants.MOBILE;
        }
        Call<GenricResponse> notificationViewResponseCall = RestClient.getAnalyticsClient(getApplicationContext(), headerMap).notificationView(prefs.getHash(), tag, PEConstants.ANDROID, device, PushEngage.getSdkVersion(), PEUtilities.getTimeZone());
        notificationViewResponseCall.enqueue(new Callback<GenricResponse>() {
            @Override
            public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                if (response.isSuccessful()) {
                    GenricResponse genricResponse = response.body();
//                    Log.d(TAG, "API Success");
                } else {
                    if (!isRetry) {
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                notificationView(tag, true);
                            }
                        }, PEConstants.RETRY_DELAY);

                    } else {
                        ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                        String jsonStr = gson.toJson(response.body());
                        ErrorLogRequest.Data data = errorLogRequest.new Data(tag, prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), jsonStr);
                        errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                        errorLogRequest.setName(PEConstants.VIEW_COUNT_TRACKING_FAILED);
                        errorLogRequest.setData(data);
                        PEUtilities.addLogs(getApplicationContext(), TAG, errorLogRequest);
//                        Log.d(TAG, "API Failure");
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
                if (!isRetry) {
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            notificationView(tag, true);
                        }
                    }, PEConstants.RETRY_DELAY);

                } else {
                    ErrorLogRequest errorLogRequest = new ErrorLogRequest();
                    ErrorLogRequest.Data data = errorLogRequest.new Data(tag, prefs.getHash(), PEConstants.MOBILE, PEUtilities.getTimeZone(), t.getMessage());
                    errorLogRequest.setApp(PEConstants.ANDROID_SDK);
                    errorLogRequest.setName(PEConstants.VIEW_COUNT_TRACKING_FAILED);
                    errorLogRequest.setData(data);
                    PEUtilities.addLogs(getApplicationContext(), TAG, errorLogRequest);
//                    Log.d(TAG, "API Failure");
                }
            }
        });
    }

    /**
     * API call to get the Channel Info based on channelId from payload
     *
     * @param channelId
     * @param notificationManager
     * @param notificationBuilder
     * @param payload
     */
    private void getChannelInfo(String channelId, NotificationManager notificationManager, NotificationCompat.Builder notificationBuilder, PayloadPOJO payload) {
        Call<ChannelResponse> channelResponseCall = RestClient.getBackendCdnClient(getApplicationContext()).getChannelInfo(prefs.getSiteKey(), channelId);
        channelResponseCall.enqueue(new Callback<ChannelResponse>() {
            @Override
            public void onResponse(@NonNull Call<ChannelResponse> call, @NonNull Response<ChannelResponse> response) {
                if (response.isSuccessful()) {
                    ChannelResponse channelResponse = response.body();
                    peRoomDatabase = PERoomDatabase.getDatabase(getApplicationContext());
                    daoInterface = peRoomDatabase.daoInterface();
                    ChannelEntity channelEntity = new ChannelEntity(String.valueOf(channelResponse.getData().getChannelId()), channelResponse.getData().getChannelName(), channelResponse.getData().getChannelDescription(),
                            String.valueOf(channelResponse.getData().getGroupId()), channelResponse.getData().getGroupName(), channelResponse.getData().getOptions().getImportance(), channelResponse.getData().getOptions().getSound(),
                            channelResponse.getData().getOptions().getSoundFile(),
                            channelResponse.getData().getOptions().getVibration(),
                            String.valueOf(channelResponse.getData().getOptions().getVibrationPattern()),
                            channelResponse.getData().getOptions().getLedColor(),
                            channelResponse.getData().getOptions().getLedColorCode(),
                            "",
                            channelResponse.getData().getOptions().getBadges(), channelResponse.getData().getOptions().getLockScreen());
                    daoInterface.insert(channelEntity);
                    setChannelInfo(channelId, notificationManager, notificationBuilder, payload, false);
//                    Log.d(TAG, "API Success");
                } else {
                    setChannelInfo(channelId, notificationManager, notificationBuilder, payload, true);
//                    Log.d(TAG, "API Failure");
                }
            }

            @Override
            public void onFailure(@NonNull Call<ChannelResponse> call, @NonNull Throwable t) {
                setChannelInfo(channelId, notificationManager, notificationBuilder, payload, true);
//                Log.d(TAG, "API Failure");
            }
        });
    }

    /**
     * API call to upgrade token when new token is generated.
     *
     * @param token
     */
    private void upgradeToken(String token) {
        prefs = new PEPrefs(this);
        UpgradeSubscriberRequest upgradeSubscriberRequest = new UpgradeSubscriberRequest();
        UpgradeSubscriberRequest.Subscription subscription = upgradeSubscriberRequest.new Subscription(token, prefs.getProjectId());
        upgradeSubscriberRequest = new UpgradeSubscriberRequest(prefs.getHash(), subscription, prefs.getSiteId());
        Call<ResponseBody> addRecordsResponseCall = RestClient.getBackendClient(getApplicationContext()).upgradeSubscriber(upgradeSubscriberRequest);
        addRecordsResponseCall.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                if (response.code() < 400) {
//                    Log.d(TAG, "Error Logged");
                } else {
//                    Log.d(TAG, "API Failure");
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
//                Log.d(TAG, "API Failure");
            }
        });
    }

    /**
     * API call to Update Subscriber status based on Notification Permission
     *
     * @param updateSubscriberStatusRequest
     */
    public void updateSubscriberStatus(UpdateSubscriberStatusRequest updateSubscriberStatusRequest) {
        updateSubscriberStatusRequest.setDeviceTokenHash(prefs.getHash());
        Call<GenricResponse> updateSubscriberStatusResponseCall = RestClient.getBackendClient(getApplicationContext()).updateSubscriberStatus(updateSubscriberStatusRequest);
        updateSubscriberStatusResponseCall.enqueue(new Callback<GenricResponse>() {
            @Override
            public void onResponse(@NonNull Call<GenricResponse> call, @NonNull Response<GenricResponse> response) {
                if (response.isSuccessful()) {
                    GenricResponse genricResponse = response.body();
                    prefs.setIsNotificationDisabled(updateSubscriberStatusRequest.getIsUnSubscribed());
                } else {
//                    Log.d(TAG, "API Failure");
                }
            }

            @Override
            public void onFailure(@NonNull Call<GenricResponse> call, @NonNull Throwable t) {
//                Log.d(TAG, "API Failure");
            }
        });
    }


}