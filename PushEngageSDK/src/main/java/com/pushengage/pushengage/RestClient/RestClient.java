package com.pushengage.pushengage.RestClient;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.R;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.helper.PEUtilities;
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest;
import com.pushengage.pushengage.model.request.AddProfileIdRequest;
import com.pushengage.pushengage.model.request.AddSegmentRequest;
import com.pushengage.pushengage.model.request.AddSubscriberRequest;
import com.pushengage.pushengage.model.request.ErrorLogRequest;
import com.pushengage.pushengage.model.request.FetchRequest;
import com.pushengage.pushengage.model.request.GoalRequest;
import com.pushengage.pushengage.model.request.RecordsRequest;
import com.pushengage.pushengage.model.request.RemoveDynamicSegmentRequest;
import com.pushengage.pushengage.model.request.RemoveSegmentRequest;
import com.pushengage.pushengage.model.request.SegmentHashArrayRequest;
import com.pushengage.pushengage.model.request.TriggerCampaignRequest;
import com.pushengage.pushengage.model.request.TriggerCampaignRequestModel;
import com.pushengage.pushengage.model.request.TriggerCampaignResponse;
import com.pushengage.pushengage.model.request.UpdateSubscriberRequest;
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest;
import com.pushengage.pushengage.model.request.UpdateTriggerStatusRequest;
import com.pushengage.pushengage.model.request.UpgradeSubscriberRequest;
import com.pushengage.pushengage.model.response.AddSubscriberResponse;
import com.pushengage.pushengage.model.response.AndroidSyncResponse;
import com.pushengage.pushengage.model.response.ChannelResponseModel;
import com.pushengage.pushengage.model.response.FetchResponse;
import com.pushengage.pushengage.model.response.NetworkResponse;
import com.pushengage.pushengage.model.response.RecordsResponse;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.Buffer;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.HTTP;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

public class RestClient {

    private static final String TAG = "RestClient";
    private static Retrofit unAuthorisedRetrofitClient;
    private static Context globalContext;
    private static PEPrefs prefs;

    public RestClient() {
    }

    public static RTApiInterface getBackendClient(Context context) {
        globalContext = context;
        prefs = new PEPrefs(context);
        unAuthorisedRetrofitClient = getRetrofitClient(null, PEConstants.BASE);
        return unAuthorisedRetrofitClient.create(RTApiInterface.class);
    }

    public static RTApiInterface getBackendCdnClient(Context context) {
        globalContext = context;
        prefs = new PEPrefs(context);
        unAuthorisedRetrofitClient = getRetrofitClient(null, PEConstants.BASE_CDN);
        return unAuthorisedRetrofitClient.create(RTApiInterface.class);
    }

    public static RTApiInterface getTriggerClient(Context context) {
        globalContext = context;
        prefs = new PEPrefs(context);
        unAuthorisedRetrofitClient = getRetrofitClient(null, PEConstants.TRIGGER);
        return unAuthorisedRetrofitClient.create(RTApiInterface.class);
    }

    public static RTApiInterface getLogClient(Context context) {
        globalContext = context;
        prefs = new PEPrefs(context);
        unAuthorisedRetrofitClient = getRetrofitClient(null, PEConstants.LOG);
        return unAuthorisedRetrofitClient.create(RTApiInterface.class);
    }

    public static RTApiInterface getAnalyticsClient(Context context, Map<String, String> headers) {
        globalContext = context;
        prefs = new PEPrefs(context);
        unAuthorisedRetrofitClient = getRetrofitClient(headers, PEConstants.ANALYTICS);
        return unAuthorisedRetrofitClient.create(RTApiInterface.class);
    }

    public static Retrofit getRetrofitClient(Map<String, String> headers, String urlType) {
        String baseUrl = getBaseUrl(urlType);

        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        if(PELogger.isLoggingEnabled()) {
            interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        } else {
            interceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
        }

        OkHttpClient.Builder okClientBuilder = new OkHttpClient().newBuilder();
        okClientBuilder.addInterceptor(interceptor);

        okClientBuilder.addInterceptor(new Interceptor() {
            @Override
            public okhttp3.Response intercept(Chain chain) throws IOException {
                Request.Builder requestBuilder = chain.request().newBuilder();
                PackageInfo pInfo = null;
                String sdkVersion = "";
                try {
                    pInfo = globalContext.getPackageManager().getPackageInfo(globalContext.getPackageName(), 0);
                    sdkVersion = pInfo.versionName;
                } catch (PackageManager.NameNotFoundException e) {
//                    e.printStackTrace();
                }
                requestBuilder.addHeader("content-type", "application/json");

                requestBuilder.addHeader("X-Pe-Client", "Android");
                requestBuilder.addHeader("X-Pe-Client-Version", Build.VERSION.RELEASE);
                requestBuilder.addHeader("X-Pe-Sdk-Version", sdkVersion);
                requestBuilder.addHeader("X-Pe-App-Id", prefs.getSiteKey());

                String userAgent = String.format("android-%s/sdk-%s/app-%s", Build.VERSION.RELEASE, sdkVersion, prefs.getSiteKey());
                requestBuilder.removeHeader("User-Agent");
                requestBuilder.addHeader("User-Agent", userAgent);

                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        requestBuilder.addHeader(entry.getKey(), entry.getValue());
                    }
                }
                Request request = requestBuilder.build();
                okhttp3.Response response = chain.proceed(request);
                if (!urlType.equals(PEConstants.ANALYTICS)) {
                    try {
                        if (response.code() == 404 && urlType.equalsIgnoreCase(PEConstants.BASE)) {
//                            Log.d(TAG, " 404 response Called");

                            try {
                                request = callAddSubscriberAPI(request, prefs.getHash());
                            } catch (Exception e) {
//                                e.printStackTrace();
                            }
                            if (request != null) {
                                response.close();
                                return chain.proceed(request);
                            }
                        }
                    } catch (Exception e) {
//                        e.printStackTrace();
                    }

                }
                return response;
            }
        }).build();

        okClientBuilder.readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build();
        Gson gson = new GsonBuilder().setLenient().create();
        Retrofit retrofitClient = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okClientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        return retrofitClient;
    }

    private static String getBaseUrl(String urlType) {
        String baseUrl = "";
        switch (urlType) {
            case PEConstants.BASE_CDN:
                if (!TextUtils.isEmpty(prefs.getBackendCdnUrl())) {
                    baseUrl = prefs.getBackendCdnUrl();
                } else {
                    switch (prefs.getEnvironment()) {
                        case PEConstants.PROD:
                            baseUrl = PEConstants.PROD_BASE_CDN_URL;
                            break;
                        case PEConstants.STG:
                            baseUrl = PEConstants.STG_BASE_CDN_URL;
                            break;
                    }
                }
                break;
            case PEConstants.BASE:
                if (!TextUtils.isEmpty(prefs.getBackendUrl())) {
                    baseUrl = prefs.getBackendUrl();
                } else {
                    switch (prefs.getEnvironment()) {
                        case PEConstants.PROD:
                            baseUrl = PEConstants.PROD_BASE_URL;
                            break;
                        case PEConstants.STG:
                            baseUrl = PEConstants.STG_BASE_URL;
                            break;
                    }
                }
                break;
            case PEConstants.TRIGGER:
                if (!TextUtils.isEmpty(prefs.getTriggerUrl())) {
                    baseUrl = prefs.getTriggerUrl();
                } else {
                    switch (prefs.getEnvironment()) {
                        case PEConstants.PROD:
                            baseUrl = PEConstants.PROD_TRIGGER_URL;
                            break;
                        case PEConstants.STG:
                            baseUrl = PEConstants.STG_TRIGGER_URL;
                            break;
                    }
                }
                break;
            case PEConstants.LOG:
                if (!TextUtils.isEmpty(prefs.getLoggerUrl())) {
                    baseUrl = prefs.getLoggerUrl();
                } else {
                    switch (prefs.getEnvironment()) {
                        case PEConstants.PROD:
                            baseUrl = PEConstants.PROD_LOG_URL;
                            break;
                        case PEConstants.STG:
                            baseUrl = PEConstants.STG_LOG_URL;
                            break;
                    }
                }
                break;
            case PEConstants.ANALYTICS:
                if (!TextUtils.isEmpty(prefs.getAnalyticsUrl())) {
                    baseUrl = prefs.getAnalyticsUrl();
                } else {
                    switch (prefs.getEnvironment()) {
                        case PEConstants.PROD:
                            baseUrl = PEConstants.PROD_ANALYTICS_URL;
                            break;
                        case PEConstants.STG:
                            baseUrl = PEConstants.STG_ANALYTICS_URL;
                            break;
                    }
                }
                break;
        }

        return baseUrl;
    }

    private static Request callAddSubscriberAPI(Request request, String oldHash) {
        String timeZone = PEUtilities.getTimeZone();
        String language = Locale.getDefault().getLanguage();
        String device = "";
        if (globalContext.getResources().getBoolean(R.bool.is_tablet)) {
            device = PEConstants.TABLET;
        } else {
            device = PEConstants.MOBILE;
        }
        String deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        String deviceModel = android.os.Build.MODEL;
        String deviceManufacturer = android.os.Build.MANUFACTURER;
        String deviceVersion = Build.VERSION.RELEASE;
        String packageName = globalContext.getPackageName();

        int width = Resources.getSystem().getDisplayMetrics().widthPixels;
        int height = Resources.getSystem().getDisplayMetrics().heightPixels;
        String screenSize = width + "*" + height;

        AddSubscriberRequest addSubscriberRequest = new AddSubscriberRequest();
        AddSubscriberRequest.Subscription subscription = addSubscriberRequest.new Subscription(prefs.getDeviceToken(), String.valueOf(prefs.getProjectId()));
        addSubscriberRequest = new AddSubscriberRequest(prefs.getSiteId(), subscription, PEConstants.ANDROID, device,
                deviceVersion, deviceModel, deviceManufacturer, timeZone, language, deviceName, screenSize, packageName, prefs.isNotificationDisabled());


        try {
            Call<AddSubscriberResponse> addSubscriberResponseCall = RestClient.getBackendClient(globalContext).addSubscriber(addSubscriberRequest, PushEngage.getSdkVersion(), String.valueOf(prefs.getEu()), String.valueOf(prefs.isGeoFetch()));
            retrofit2.Response<AddSubscriberResponse> response = addSubscriberResponseCall.execute();
            AddSubscriberResponse apiResponse = response.body();
            if(apiResponse != null) {
                prefs.setHash(apiResponse.getData().getSubscriberHash());
            }

            RequestBody requestBody = request.body();
            HttpUrl.Builder urlBuilder = request.url().newBuilder();
            List<String> segments = request.url().pathSegments();

            for (int i = 0; i < segments.size(); i++) {
                if (oldHash.equalsIgnoreCase(segments.get(i))) {
                    if (apiResponse != null) {
                        urlBuilder.setPathSegment(i, apiResponse.getData().getSubscriberHash());
                    }
                }
            }
            if (apiResponse != null) {
                requestBody = processApplicationJsonRequestBody(requestBody, apiResponse.getData().getSubscriberHash());
            }

            if (requestBody != null) {
                Request.Builder requestBuilder = request.newBuilder();
                request = requestBuilder
                        .url(urlBuilder.build())
                        .post(requestBody)
                        .build();
            } else {
                Request.Builder requestBuilder = request.newBuilder();
                request = requestBuilder
                        .url(urlBuilder.build())
                        .build();
            }

            return request;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private static RequestBody processApplicationJsonRequestBody(RequestBody requestBody, String hash) {
        String customReq = bodyToString(requestBody);
        try {
            JSONObject obj = new JSONObject(customReq);
            if (obj.has("device_token_hash")) {
                obj.put("device_token_hash", hash);
            }
            if (obj.has("device_hash")) {
                obj.put("device_hash", hash);
            }
            return RequestBody.create(requestBody.contentType(), obj.toString());
        } catch (JSONException e) {
//            e.printStackTrace();
        }
        return null;
    }

    private static String bodyToString(final RequestBody request) {
        try {
            final RequestBody copy = request;
            final Buffer buffer = new Buffer();
            if (copy != null)
                copy.writeTo(buffer);
            else
                return "";
            return buffer.readUtf8();
        } catch (final IOException e) {
            return "did not work";
        }
    }

    public interface RTApiInterface {
        @POST("subscriber/add")
        Call<AddSubscriberResponse> addSubscriber(@Body AddSubscriberRequest addSubscriberRequest,
                @Query("swv") String swv, @Query("is_eu") String is_eu, @Query("geo_fetch") String geo_fetch);

        @PUT()
        Call<RecordsResponse> records(@Body RecordsRequest recordsRequest);

        @POST("subscriber/updatetriggerstatus")
        Call<NetworkResponse> automatedNotification(@Body UpdateTriggerStatusRequest updateTriggerStatusRequest,
                @Query("swv") String swv, @Query("bv") String bv);

        @POST("goals")
        Call<NetworkResponse> sendGoal(@Body GoalRequest goalRequest, @Query("swv") String swv, @Query("bv") String bv);

        @PUT(".")
        Call<TriggerCampaignResponse> sendTriggerEvent(@Body TriggerCampaignRequestModel triggerCampaignRequestModel);

        @POST("alerts")
        Call<NetworkResponse> addAlert(@Body Map<String, Object> requestBody, @Query("swv") String swv,
                @Query("bv") String bv);

        @POST("notification/fetch")
        Call<FetchResponse> fetch(@Body FetchRequest fetchRequest);

        @GET("notification/click")
        Call<NetworkResponse> notificationClick(@Query("device_token_hash") String device_hash,
                @Query("tag") String tag, @Query("action") String action, @Query("device_type") String device_type,
                @Query("device") String device, @Query("swv") String swv, @Query("timezone") String timezone);

        @GET("notification/view")
        Call<NetworkResponse> notificationView(@Query("device_token_hash") String device_token_hash,
                @Query("tag") String tag, @Query("device_type") String device_type, @Query("device") String device,
                @Query("swv") String swv, @Query("timezone") String timezone);

        @PUT("subscriber/{id}")
        Call<NetworkResponse> updateSubscriberHash(@Path("id") String id,
                @Body UpdateSubscriberRequest updateSubscriberRequest, @Query("swv") String swv,
                @Query("is_eu") String is_eu, @Query("geo_fetch") String geo_fetch);

        @GET("subscriber/{id}")
        Call<NetworkResponse> subscriberDetails(@Path("id") String id, @Query("fields") String fields);

        @GET("subscriber/{id}/attributes")
        Call<NetworkResponse> getSubscriberAttributes(@Path("id") String id);

        @HTTP(method = "DELETE", path = "subscriber/{id}/attributes", hasBody = true)
        Call<NetworkResponse> deleteSubscriberAttributes(@Path("id") String id, @Body List<String> value);

        @PUT("subscriber/{id}/attributes")
        Call<NetworkResponse> addAttributes(@Path("id") String id, @Body JsonObject jsonObject);

        @POST("subscriber/{id}/attributes")
        Call<NetworkResponse> setAttributes(@Path("id") String id, @Body JsonObject jsonObject);

        @POST("subscriber/profile-id/add")
        Call<NetworkResponse> addProfileId(@Body AddProfileIdRequest addProfileIdRequest);

        @POST("subscriber/segments/add")
        Call<NetworkResponse> addSegments(@Body AddSegmentRequest addSegmentRequest);

        @POST("subscriber/segments/remove")
        Call<NetworkResponse> removeSegments(@Body RemoveSegmentRequest removeSegmentRequest);

        @POST("subscriber/dynamicSegments/add")
        Call<NetworkResponse> addDynamicSegments(@Body AddDynamicSegmentRequest addDynamicSegmentRequest);

        @POST("subscriber/dynamicSegments/remove")
        Call<NetworkResponse> removeDynamicSegments(@Body RemoveDynamicSegmentRequest removeDynamicSegmentRequest);

        @POST("subscriber/segments/segmentHashArray")
        Call<NetworkResponse> getSegmentHashArray(@Body SegmentHashArrayRequest segmentHashArrayRequest);

        @GET("subscriber/check/{id}")
        Call<NetworkResponse> checkSubscriberHash(@Path("id") String id);

        @POST("subscriber/updatetriggerstatus")
        Call<NetworkResponse> updateTriggerStatus(@Body UpdateTriggerStatusRequest updateTriggerStatusRequest);

        @POST("subscriber/updatesubscriberstatus")
        Call<NetworkResponse> updateSubscriberStatus(@Body UpdateSubscriberStatusRequest updateSubscriberStatusRequest);

        @POST("logs")
        Call<ResponseBody> logs(@Body ErrorLogRequest errorLogRequest);

        @GET("sites/{site_key}/sync/android")
        Call<AndroidSyncResponse> androidSync(@Path("site_key") String site_key);

        @GET("sites/{site_key}/android/notification-channels/{channel_id}")
        Call<ChannelResponseModel> getChannelInfo(@Path("site_key") String site_key, @Path("channel_id") String channel_id);

        @PUT("subscriber/upgrade")
        Call<ResponseBody> upgradeSubscriber(@Body UpgradeSubscriberRequest upgradeSubscriberRequest);
    }
}