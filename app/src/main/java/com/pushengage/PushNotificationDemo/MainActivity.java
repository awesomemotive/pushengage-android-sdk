package com.pushengage.PushNotificationDemo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.pushengage.PushNotificationDemo.TriggerCampaign.TriggerCampaignActivity;
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback;
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnTriggerCampaign, btnSubscriberDetails, btnGetAttributes,
            btnAddAttributes, btnSetAttributes, btnRemoveAttributes, btnAddProfileId, btnIdentify, btnLogout, btnAddSegment,
            btnRemoveSegment, btnAddDynamicSegment, buttonSendGoal, btnTrackEvent, btnRequestNotificationPermission,
            btnComposeTest, btnGetNotificationPermissionStatus, btnGetSubscriptionStatus,
            btnGetSubscriptionNotificationStatus, btnSubscribe, btnUnsubscribe, btnGetSubscriberId,
            btnSetBadgeCount, btnClearBadgeCount;
    private TextView tvDeviceToken, tvDeviceHash, tvEnvChip, tvAppIdValue;
    private final Gson gson = new Gson();
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private ProgressBar progressBar;

    private android.view.View eventLogPanel;
    private android.widget.ScrollView eventLogScroll;
    private TextView tvEventLog, tvEventLogTitle, tvEventLogChevron;
    private boolean eventLogExpanded = false;
    private final SdkEventLog.Listener eventLogListener = this::renderEventLog;

    private final Handler deviceHashHandler = new Handler(Looper.getMainLooper());
    private final Runnable deviceHashPoll = new Runnable() {
        @Override
        public void run() {
            String hash = PushEngage.getDeviceTokenHash();
            if (!TextUtils.isEmpty(hash)) {
                tvDeviceHash.setText(hash);
                tvDeviceHash.setAlpha(1f);
                return;
            }
            tvDeviceHash.setText(getString(R.string.waiting_for_token));
            tvDeviceHash.setAlpha(0.6f);
            deviceHashHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setSupportActionBar(findViewById(R.id.toolbar));

        btnTriggerCampaign = findViewById(R.id.btn_trigger);
        tvDeviceToken = findViewById(R.id.tv_device_token);
        tvDeviceHash = findViewById(R.id.tv_device_hash);
        btnSubscriberDetails = findViewById(R.id.btn_hash_details);
        btnGetAttributes = findViewById(R.id.btn_get_attributes);
        btnAddAttributes = findViewById(R.id.btn_add_attributes);
        btnSetAttributes = findViewById(R.id.btn_set_attributes);
        btnRemoveAttributes = findViewById(R.id.btn_remove_attributes);
        btnAddProfileId = findViewById(R.id.btn_add_profile_id);
        btnIdentify = findViewById(R.id.btn_identify);
        btnLogout = findViewById(R.id.btn_logout);
        btnAddSegment = findViewById(R.id.btn_add_segment);
        btnRemoveSegment = findViewById(R.id.btn_remove_segment);
        btnAddDynamicSegment = findViewById(R.id.btn_add_dynamic_segment);
        buttonSendGoal = findViewById(R.id.btn_send_goal);
        btnTrackEvent = findViewById(R.id.btn_track_event);
        btnRequestNotificationPermission = findViewById(R.id.btn_request_notification_permission);
        btnGetNotificationPermissionStatus = findViewById(R.id.btn_get_notification_permission_status);
        btnSetBadgeCount = findViewById(R.id.btn_set_badge_count);
        btnClearBadgeCount = findViewById(R.id.btn_clear_badge_count);
        btnGetSubscriptionStatus = findViewById(R.id.btn_get_subscription_status);
        btnGetSubscriptionNotificationStatus = findViewById(R.id.btn_get_subscription_notification_status);
        btnSubscribe = findViewById(R.id.btn_subscribe);
        btnUnsubscribe = findViewById(R.id.btn_unsubscribe);
        btnGetSubscriberId = findViewById(R.id.btn_get_subscriber_id);
        btnComposeTest = findViewById(R.id.btn_compose_test);
        progressBar = findViewById(R.id.progress_bar);
        tvEnvChip = findViewById(R.id.tv_env_chip);
        tvAppIdValue = findViewById(R.id.tv_app_id_value);
        findViewById(R.id.card_current_config).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        renderCurrentConfig();
        setupEventLogPanel();
        setupSections();

        tvDeviceHash.setOnClickListener(v -> copyToClipboard("Device hash", tvDeviceHash.getText().toString()));

        // PushEngage.setSmallIconResource("pe_icon");
        // Polling is driven by onResume/onPause — see lifecycle hooks below.

        buttonSendGoal.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, GoalActivity.class);
            startActivity(intent);
        });

        btnTrackEvent.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, TrackEventActivity.class);
            startActivity(intent);
        });

        btnTriggerCampaign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, TriggerCampaignActivity.class);
                startActivity(intent);
            }
        });

        btnSubscriberDetails.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.subscriber_details),
                        getString(R.string.subscriber_details_request), Constants.SUBSCRIBER_DETAILS);
            }
        });

        btnGetAttributes.setOnClickListener(v -> {
            showProgressDialog();
            PushEngage.getSubscriberAttributes(loggingCallback("getSubscriberAttributes", null, null));
        });

        btnAddAttributes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.add_attributes), getString(R.string.set_attributes_request),
                        Constants.ADD_ATTRIBUTES);
            }
        });

        btnSetAttributes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.set_attributes), getString(R.string.set_attributes_request),
                        Constants.SET_ATTRIBUTES);
            }
        });

        btnRemoveAttributes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.delete_attributes), getString(R.string.delete_attributes_request),
                        Constants.REMOVE_ATTRIBUTES);
            }
        });

        btnAddProfileId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.add_profile_id), getString(R.string.enter_profile_id),
                        Constants.ADD_PROFILE_ID);
            }
        });

        btnIdentify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.identify), getString(R.string.identify_request),
                        Constants.IDENTIFY);
            }
        });

        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.logout), getString(R.string.logout_request),
                        Constants.LOGOUT);
            }
        });

        btnAddSegment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.add_segment), getString(R.string.add_segment_request),
                        Constants.ADD_SEGMENT);
            }
        });

        btnRemoveSegment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.delete_segment), getString(R.string.delete_segment_request),
                        Constants.DELETE_SEGMENT);
            }
        });

        btnAddDynamicSegment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getRequestFromUser(getString(R.string.add_dynamic_segments),
                        getString(R.string.add_dynamic_segment_request), Constants.ADD_DYNAMIC_SEGMENT);
            }
        });

        btnRequestNotificationPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotificationPermissionUsingSDK();
            }
        });

        btnGetNotificationPermissionStatus.setOnClickListener(v -> {
            String status = PushEngage.getNotificationPermissionStatus();
            SdkEventLog.get().info("getNotificationPermissionStatus", status);
            showResponse("Notification Permission Status", status);
        });

        btnSetBadgeCount.setOnClickListener(v -> getRequestFromUser(
                getString(R.string.set_badge_count),
                getString(R.string.set_badge_count_request),
                Constants.SET_BADGE_COUNT));

        btnClearBadgeCount.setOnClickListener(v -> {
            PushEngage.setBadgeCount(0);
            SdkEventLog.get().info("setBadgeCount", "0 (cleared)");
            showResponse("setBadgeCount", getString(R.string.set_badge_count_cleared));
        });

        btnGetSubscriptionStatus.setOnClickListener(v -> {
            showProgressDialog();
            PushEngage.getSubscriptionStatus(loggingCallback("getSubscriptionStatus", null, null));
        });

        btnGetSubscriptionNotificationStatus.setOnClickListener(v -> {
            showProgressDialog();
            PushEngage.getSubscriptionNotificationStatus(loggingCallback("getSubscriptionNotificationStatus", null, null));
        });

        btnSubscribe.setOnClickListener(v -> {
            showProgressDialog();
            PushEngage.subscribe(MainActivity.this, loggingCallback("subscribe", null, null));
        });

        btnUnsubscribe.setOnClickListener(v -> {
            showProgressDialog();
            PushEngage.unsubscribe(loggingCallback("unsubscribe", null, null));
        });

        btnGetSubscriberId.setOnClickListener(v -> {
            showProgressDialog();
            PushEngage.getSubscriberId(loggingCallback("getSubscriberId", null, null));
        });

        btnComposeTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ComposeTestActivity.class);
                startActivity(intent);
            }
        });

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCurrentConfig();
        SdkEventLog.get().addListener(eventLogListener);
        renderEventLog();
        // Show the device hash as soon as it becomes available — typically after
        // permission grant + FCM token + sync + subscriber/add complete. No fixed
        // deadline: poller runs while the activity is in front, stops in onPause.
        deviceHashHandler.removeCallbacks(deviceHashPoll);
        deviceHashHandler.post(deviceHashPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        SdkEventLog.get().removeListener(eventLogListener);
        deviceHashHandler.removeCallbacks(deviceHashPoll);
    }

    private void setupSections() {
        setupSection(R.id.hdr_diagnostics, R.id.body_diagnostics, R.id.chev_diagnostics);
        setupSection(R.id.hdr_subscription, R.id.body_subscription, R.id.chev_subscription);
        setupSection(R.id.hdr_permissions, R.id.body_permissions, R.id.chev_permissions);
        setupSection(R.id.hdr_profile_attributes, R.id.body_profile_attributes, R.id.chev_profile_attributes);
        setupSection(R.id.hdr_segments, R.id.body_segments, R.id.chev_segments);
        setupSection(R.id.hdr_goals_events, R.id.body_goals_events, R.id.chev_goals_events);
        setupSection(R.id.hdr_triggers, R.id.body_triggers, R.id.chev_triggers);
        setupSection(R.id.hdr_other, R.id.body_other, R.id.chev_other);
    }

    private void setupSection(int headerId, int bodyId, int chevronId) {
        View header = findViewById(headerId);
        View body = findViewById(bodyId);
        TextView chevron = findViewById(chevronId);
        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            chevron.setText(expanded ? "▶" : "▼");
        });
    }

    private void setupEventLogPanel() {
        eventLogPanel = findViewById(R.id.event_log_panel);
        eventLogScroll = findViewById(R.id.event_log_scroll);
        tvEventLog = findViewById(R.id.tv_event_log);
        tvEventLogTitle = findViewById(R.id.tv_event_log_title);
        tvEventLogChevron = findViewById(R.id.tv_event_log_chevron);
        View header = findViewById(R.id.event_log_header);
        Button btnClear = findViewById(R.id.btn_event_log_clear);

        header.setOnClickListener(v -> toggleEventLog());
        btnClear.setOnClickListener(v -> SdkEventLog.get().clear());

        renderEventLog();
    }

    private void toggleEventLog() {
        eventLogExpanded = !eventLogExpanded;
        eventLogScroll.setVisibility(eventLogExpanded ? View.VISIBLE : View.GONE);
        tvEventLogChevron.setText(eventLogExpanded ? "▼" : "▲");
        if (eventLogExpanded) scrollLogToBottom();
    }

    private void renderEventLog() {
        if (tvEventLog == null) return;
        List<SdkEventLog.Entry> entries = SdkEventLog.get().snapshot();
        tvEventLogTitle.setText("Event log (" + entries.size() + ")");
        StringBuilder sb = new StringBuilder();
        for (SdkEventLog.Entry e : entries) {
            String marker = e.status == SdkEventLog.Status.SUCCESS ? "✓"
                    : e.status == SdkEventLog.Status.FAILURE ? "✗" : "·";
            sb.append(SdkEventLog.formatTimestamp(e.timestampMs))
                    .append("  ").append(marker)
                    .append("  ").append(e.tag);
            if (!TextUtils.isEmpty(e.message)) {
                sb.append("  ").append(e.message);
            }
            sb.append("\n");
        }
        tvEventLog.setText(sb.toString());
        if (eventLogExpanded) scrollLogToBottom();
    }

    private void scrollLogToBottom() {
        eventLogScroll.post(() -> eventLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Returns a PushEngageResponseCallback that logs success/failure to the event panel
     * and delegates to the provided handlers. Either handler may be null.
     */
    private PushEngageResponseCallback loggingCallback(String tag,
                                                       androidx.core.util.Consumer<Object> onSuccess,
                                                       androidx.core.util.Consumer<String> onFailure) {
        return new PushEngageResponseCallback() {
            @Override
            public void onSuccess(Object responseObject) {
                String preview = responseObject == null ? "(no body)" : gson.toJson(responseObject);
                if (preview.length() > 120) preview = preview.substring(0, 117) + "…";
                SdkEventLog.get().success(tag, preview);
                showResponse(tag + " — success", responseObject);
                if (onSuccess != null) onSuccess.accept(responseObject);
            }

            @Override
            public void onFailure(Integer errorCode, String errorMessage) {
                SdkEventLog.get().failure(tag, "(" + errorCode + ") " + errorMessage);
                String body = "Error code: " + errorCode + "\n\n" +
                        (TextUtils.isEmpty(errorMessage) ? "(no message)" : errorMessage);
                showResponse(tag + " — failed", body);
                if (onFailure != null) onFailure.accept(errorMessage);
            }
        };
    }

    private void copyToClipboard(String label, String value) {
        if (TextUtils.isEmpty(value) || "-".equals(value)) {
            Toast.makeText(this, label + " not available yet", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
    }

    private void renderCurrentConfig() {
        DemoPrefs prefs = new DemoPrefs(this);
        String appId = prefs.getAppId();
        tvAppIdValue.setText(TextUtils.isEmpty(appId) ? getString(R.string.settings_app_id_empty) : appId);
        tvEnvChip.setText(prefs.getEnvironment());
        int chipColor = ContextCompat.getColor(this,
                PEConstants.STG.equals(prefs.getEnvironment()) ? R.color.purple_500 : R.color.green);
        tvEnvChip.setBackgroundColor(chipColor);
    }

    /**
     * Request notification permission using the PushEngage SDK
     * SDK automatically calls subscribe when permission is granted
     */
    private void requestNotificationPermissionUsingSDK() {
        PushEngage.requestNotificationPermission(this, new PushEngagePermissionCallback() {
            @Override
            public void onPermissionResult(boolean granted, Error error) {
                if (granted) {
                    SdkEventLog.get().success("requestNotificationPermission", "granted (auto-subscribed)");
                    showResponse("requestNotificationPermission — success",
                            "Permission granted. SDK auto-subscribed.");
                } else {
                    String detail = "denied" + (error != null ? " — " + error.getMessage() : "");
                    SdkEventLog.get().failure("requestNotificationPermission", detail);
                    showResponse("requestNotificationPermission — failed",
                            error != null ? error.getMessage() : "Permission denied");
                }
            }
        });
    }

    public void showResponse(String title, Object payload) {
        hideProgressDialog();
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;

        String emptyMessage = "Operation completed successfully.";
        String body;
        if (payload == null) {
            body = emptyMessage;
        } else if (payload instanceof String) {
            String raw = (String) payload;
            body = TextUtils.isEmpty(raw) ? emptyMessage : tryPrettyPrintJson(raw);
        } else {
            body = prettyGson.toJson(payload);
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_response, null);
        ((TextView) content.findViewById(R.id.tv_response_title)).setText(title);
        ((TextView) content.findViewById(R.id.tv_response_body)).setText(body);
        content.findViewById(R.id.btn_response_copy).setOnClickListener(v -> copyToClipboard(title, body));
        content.findViewById(R.id.btn_response_close).setOnClickListener(v -> sheet.dismiss());
        sheet.setContentView(content);
        sheet.show();
    }

    private String tryPrettyPrintJson(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        try {
            JsonElement el = prettyGson.fromJson(raw, JsonElement.class);
            if (el != null && (el.isJsonObject() || el.isJsonArray())) return prettyGson.toJson(el);
        } catch (Exception ignored) {
        }
        return raw;
    }

    public void getRequestFromUser(String title, String message, String request) {
        hideProgressDialog();
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_request, null);
        ((TextView) content.findViewById(R.id.tv_request_title)).setText(title);
        ((TextView) content.findViewById(R.id.tv_request_hint)).setText(message);
        EditText etRequest = content.findViewById(R.id.et_request_value);

        switch (request) {
            case Constants.SUBSCRIBER_DETAILS:
                etRequest.setText(PushEngage.SubscriberFields.City + "," + PushEngage.SubscriberFields.Country + ","
                        + PushEngage.SubscriberFields.State + "," +
                        PushEngage.SubscriberFields.Device + "," + PushEngage.SubscriberFields.DeviceType + ","
                        + PushEngage.SubscriberFields.ProfileId + "," + PushEngage.SubscriberFields.Segments
                        + "," + PushEngage.SubscriberFields.Timezone + "," + PushEngage.SubscriberFields.TsCreated);
                break;
            case Constants.ADD_DYNAMIC_SEGMENT:
                etRequest.setText("[\n  {\n    \"name\": \"sports\",\n    \"duration\": 5\n  }\n]");
                break;
            case Constants.ADD_ATTRIBUTES:
            case Constants.SET_ATTRIBUTES:
                etRequest.setText("{\n  \"age\": 25,\n  \"height\": 6.1\n}");
                break;
            case Constants.IDENTIFY:
                etRequest.setText("{\n  \"email\": \"jane@example.com\",\n  \"profile_id\": \"user_42\"\n}");
                break;
            case Constants.LOGOUT:
                etRequest.setText("email, profile_id");
                break;
            case Constants.SET_BADGE_COUNT:
                etRequest.setText("5");
                break;
            default:
                break;
        }

        content.findViewById(R.id.btn_request_cancel).setOnClickListener(v -> sheet.dismiss());
        content.findViewById(R.id.btn_request_ok).setOnClickListener(v -> {
            String value = etRequest.getText().toString();
            sheet.dismiss();
            executeRequest(request, value);
        });
        sheet.setContentView(content);
        sheet.show();
    }

    private void executeRequest(String request, String value) {
        switch (request) {
                    case Constants.SUBSCRIBER_DETAILS:
                        try {
                            List<String> subscriberDetailsList = new ArrayList<String>(Arrays.asList(value.split(",")));
                            showProgressDialog();
                            PushEngage.getSubscriberDetails(subscriberDetailsList,
                                    loggingCallback("getSubscriberDetails", null, null));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;
                    case Constants.ADD_ATTRIBUTES:
                        try {
                            JSONObject jsonObject = new JSONObject(value);
                            PushEngage.addSubscriberAttributes(jsonObject,
                                    loggingCallback("addSubscriberAttributes", null, null));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;

                    case Constants.SET_ATTRIBUTES:
                        try {
                            JSONObject jsonObject = new JSONObject(value);
                            PushEngage.setSubscriberAttributes(jsonObject,
                                    loggingCallback("setSubscriberAttributes", null, null));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;

                    case Constants.REMOVE_ATTRIBUTES:
                        if (TextUtils.isEmpty(value)) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            try {
                                List<String> attributeList = new ArrayList<String>(Arrays.asList(value.split(",")));
                                PushEngage.deleteSubscriberAttributes(attributeList,
                                        loggingCallback("deleteSubscriberAttributes", null, null));
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                        Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                        }
                        break;
                    case Constants.ADD_SEGMENT:
                        if (TextUtils.isEmpty(value)) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            try {
                                List<String> segmentList = new ArrayList<String>(Arrays.asList(value.split(",")));
                                PushEngage.addSegment(segmentList,
                                        loggingCallback("addSegment", null, null));
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                        Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                        }
                        break;
                    case Constants.DELETE_SEGMENT:
                        if (TextUtils.isEmpty(value)) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            try {
                                List<String> segmentList = new ArrayList<String>(Arrays.asList(value.split(",")));
                                PushEngage.removeSegment(segmentList,
                                        loggingCallback("removeSegment", null, null));
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                        Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                        }
                        break;
                    case Constants.ADD_DYNAMIC_SEGMENT:
                        try {
                            AddDynamicSegmentRequest addDynamicSegmentRequest = new AddDynamicSegmentRequest();
                            List<AddDynamicSegmentRequest.Segment> segments = new ArrayList<>();
                            JSONArray jsonArray = new JSONArray(value);
                            for (int i = 0; i < jsonArray.length(); i++) {
                                JSONObject jsonObject = jsonArray.getJSONObject(i);
                                AddDynamicSegmentRequest.Segment segment = addDynamicSegmentRequest.new Segment(
                                        jsonObject.getString("name"), jsonObject.getInt("duration"));
                                segments.add(segment);
                            }
                            PushEngage.addDynamicSegment(segments,
                                    loggingCallback("addDynamicSegment", null, null));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;
                    case Constants.ADD_PROFILE_ID:
                        if (!TextUtils.isEmpty(value)) {
                            PushEngage.addProfileId(value.replaceAll("\\n", ""),
                                    loggingCallback("addProfileId", null, null));
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case Constants.IDENTIFY:
                        try {
                            JSONObject jsonObject = new JSONObject(value);
                            PushEngage.identify(jsonObject,
                                    loggingCallback("identify", null, null));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;
                    case Constants.LOGOUT:
                        try {
                            List<String> logoutFieldNames;
                            if (TextUtils.isEmpty(value.trim())) {
                                logoutFieldNames = new ArrayList<>();
                            } else {
                                logoutFieldNames = new ArrayList<>();
                                for (String name : value.split(",")) {
                                    String trimmed = name.trim();
                                    if (!trimmed.isEmpty()) logoutFieldNames.add(trimmed);
                                }
                            }
                            PushEngage.logout(logoutFieldNames,
                                    loggingCallback("logout", null, null));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;
                    case Constants.SET_BADGE_COUNT:
                        try {
                            int count = Integer.parseInt(value.trim());
                            PushEngage.setBadgeCount(count);
                            SdkEventLog.get().info("setBadgeCount", String.valueOf(count));
                            String message = count == 0
                                    ? getString(R.string.set_badge_count_cleared)
                                    : getString(R.string.set_badge_count_applied, String.valueOf(count));
                            showResponse("setBadgeCount", message);
                        } catch (NumberFormatException e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                        }
                        break;
            default:
                break;
        }
    }

    public void showProgressDialog() {
        progressBar.setVisibility(View.VISIBLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    public void hideProgressDialog() {
        if (progressBar != null)
            progressBar.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }
}