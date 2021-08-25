package com.pushengage.pushengage.helper;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import androidx.annotation.NonNull;
import com.pushengage.pushengage.RestClient.RestClient;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PEUtilities {
    public static List<Address> getAddress(Context context, double latitude, double longitude) {
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());
        List<Address> addresses = null;
        try {
            addresses = geocoder.getFromLocation(latitude, longitude, 1);
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return addresses;
    }

    public static String getTimeZone() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"),
                Locale.getDefault());
        Date currentLocalTime = calendar.getTime();
        DateFormat date = new SimpleDateFormat("ZZZZZ", Locale.getDefault());
        return date.format(currentLocalTime);
    }

    public static Integer generateRandomInt() {
        int min = 1;
        int max = Integer.MAX_VALUE;

        Random r = new Random();
        return r.nextInt(max - min + 1) + min;
    }

    /**
     * Method to check the network connection
     *
     * @return Return the connection status
     */
    public static Boolean checkNetworkConnection(Context context) {
        ConnectivityManager connectivityManager;
        NetworkInfo wifiInfo, mobileInfo;
        try {
            connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            mobileInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (wifiInfo.getState() == NetworkInfo.State.CONNECTED || mobileInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * @param context
     * @param TAG
     * @param log
     */
    public static void addLogs(Context context, String TAG, ErrorLogRequest log) {
        Call<ResponseBody> addRecordsResponseCall = RestClient.getLogClient(context).logs(log);
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

    public static String apiPreValidate(Context context) {
        PEPrefs prefs = new PEPrefs(context);
        // check network status
        if(!checkNetworkConnection(context)){
            return PEConstants.NETWORK_ISSUE;
        }
        // site status check
        if (!prefs.getSiteStatus().equalsIgnoreCase(PEConstants.ACTIVE)) {
            return PEConstants.SITE_NOT_ACTIVE;
        }
        // don't call any API's for deleted subscribers until permission changes
        // to allow
        // both permission and subscriberDeleted checked to catch scenario of sdk
        // race error, where permission is allowed but subscriber is marked deleted
        // So, for any API call status 404 should be checked,
        // if received 404 just call add() to insert subscriber
        if (prefs.isNotificationDisabled() == 1 && prefs.isSubscriberDeleted()) {
            return PEConstants.USER_NOT_SUBSCRIBED;
        }

        return PEConstants.VALID;
    }
}
