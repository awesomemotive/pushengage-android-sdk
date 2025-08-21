package com.pushengage.PushNotificationDemo;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.pushengage.PushNotificationDemo.TriggerCampaign.TriggerCampaignActivity;
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback;
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnTriggerCampaign, btnSubscriberDetails, btnGetAttributes,
            btnAddAttributes, btnSetAttributes, btnRemoveAttributes, btnAddProfileId, btnAddSegment,
            btnRemoveSegment, btnAddDynamicSegment, buttonSendGoal, btnRequestNotificationPermission, btnComposeTest,
            btnGetNotificationPermissionStatus, btnGetSubscriptionStatus, btnGetSubscriptionNotificationStatus,
            btnSubscribe, btnUnsubscribe, btnGetSubscriberId;
    private TextView tvDeviceToken, tvDeviceHash;
    private Gson gson = new Gson();
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnTriggerCampaign = findViewById(R.id.btn_trigger);
        tvDeviceToken = findViewById(R.id.tv_device_token);
        tvDeviceHash = findViewById(R.id.tv_device_hash);
        btnSubscriberDetails = findViewById(R.id.btn_hash_details);
        btnGetAttributes = findViewById(R.id.btn_get_attributes);
        btnAddAttributes = findViewById(R.id.btn_add_attributes);
        btnSetAttributes = findViewById(R.id.btn_set_attributes);
        btnRemoveAttributes = findViewById(R.id.btn_remove_attributes);
        btnAddProfileId = findViewById(R.id.btn_add_profile_id);
        btnAddSegment = findViewById(R.id.btn_add_segment);
        btnRemoveSegment = findViewById(R.id.btn_remove_segment);
        btnAddDynamicSegment = findViewById(R.id.btn_add_dynamic_segment);
        buttonSendGoal = findViewById(R.id.btn_send_goal);
        btnRequestNotificationPermission = findViewById(R.id.btn_request_notification_permission);
        btnGetNotificationPermissionStatus = findViewById(R.id.btn_get_notification_permission_status);
        btnGetSubscriptionStatus = findViewById(R.id.btn_get_subscription_status);
        btnGetSubscriptionNotificationStatus = findViewById(R.id.btn_get_subscription_notification_status);
        btnSubscribe = findViewById(R.id.btn_subscribe);
        btnUnsubscribe = findViewById(R.id.btn_unsubscribe);
        btnGetSubscriberId = findViewById(R.id.btn_get_subscriber_id);
        btnComposeTest = findViewById(R.id.btn_compose_test);
        progressBar = findViewById(R.id.progress_bar);

        // PushEngage.setSmallIconResource("pe_icon");

        if (TextUtils.isEmpty(PushEngage.getDeviceTokenHash())) {
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tvDeviceHash.setText(PushEngage.getDeviceTokenHash());
                }
            }, 15000);
        } else {
            tvDeviceHash.setText(PushEngage.getDeviceTokenHash());
        }

        buttonSendGoal.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, GoalActivity.class);
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

        btnGetAttributes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgressDialog();
                PushEngage.getSubscriberAttributes(new PushEngageResponseCallback() {
                    @Override
                    public void onSuccess(Object responseObject) {
                        String response = gson.toJson(responseObject);
                        showResponse(getString(R.string.attributes), response);
                    }

                    @Override
                    public void onFailure(Integer errorCode, String errorMessage) {
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            }
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

        btnGetNotificationPermissionStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String status = PushEngage.getNotificationPermissionStatus();
                showResponse("Notification Permission Status", status);
            }
        });

        btnGetSubscriptionStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgressDialog();
                PushEngage.getSubscriptionStatus(new PushEngageResponseCallback() {
                    @Override
                    public void onSuccess(Object responseObject) {
                        String response = gson.toJson(responseObject);
                        showResponse("Subscription Status", response);
                    }

                    @Override
                    public void onFailure(Integer errorCode, String errorMessage) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnGetSubscriptionNotificationStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgressDialog();
                PushEngage.getSubscriptionNotificationStatus(new PushEngageResponseCallback() {
                    @Override
                    public void onSuccess(Object responseObject) {
                        String response = gson.toJson(responseObject);
                        showResponse("Subscription Notification Status", response);
                    }

                    @Override
                    public void onFailure(Integer errorCode, String errorMessage) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnSubscribe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgressDialog();
                PushEngage.subscribe(MainActivity.this, new PushEngageResponseCallback() {
                    @Override
                    public void onSuccess(Object responseObject) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, "Subscribed successfully!", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(Integer errorCode, String errorMessage) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnUnsubscribe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgressDialog();
                PushEngage.unsubscribe(new PushEngageResponseCallback() {
                    @Override
                    public void onSuccess(Object responseObject) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, "Unsubscribed successfully!", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(Integer errorCode, String errorMessage) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnGetSubscriberId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgressDialog();
                PushEngage.getSubscriberId(new PushEngageResponseCallback() {
                    @Override
                    public void onSuccess(Object responseObject) {
                        hideProgressDialog();
                        String subscriberId = (String) responseObject;
                        if (subscriberId != null) {
                            showResponse("Subscriber ID", "Subscriber ID: " + subscriberId);
                        } else {
                            showResponse("Subscriber ID", "User is not subscribed");
                        }
                    }

                    @Override
                    public void onFailure(Integer errorCode, String errorMessage) {
                        hideProgressDialog();
                        Toast.makeText(MainActivity.this, "Error getting subscriber ID: " + errorMessage,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnComposeTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ComposeTestActivity.class);
                startActivity(intent);
            }
        });

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
                    // Permission granted - SDK automatically calls subscribe
                    Log.d("MainActivity", "Notification permission granted");
                    Toast.makeText(MainActivity.this, "Permission granted and subscribed!", Toast.LENGTH_SHORT).show();
                } else {
                    // Permission denied, handle accordingly
                    Log.d("MainActivity", "Notification permission denied");
                    Toast.makeText(MainActivity.this, "Permission denied!", Toast.LENGTH_SHORT).show();
                    if (error != null) {
                        Log.e("MainActivity", "Permission error: " + error.getMessage());
                    }
                }
            }
        });
    }

    public void showResponse(String title, String response) {
        hideProgressDialog();
        AlertDialog.Builder alert = new AlertDialog.Builder(MainActivity.this);
        alert.setCancelable(false);
        LinearLayout llBase = new LinearLayout(MainActivity.this);
        TextView tvResponse = new TextView(MainActivity.this);
        alert.setMessage(title);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(30, 10, 30, 10);
        tvResponse.setLayoutParams(params);
        tvResponse.setText(response);
        llBase.addView(tvResponse);
        alert.setView(llBase);
        alert.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
            }
        });
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
            alert.show();
    }

    public void getRequestFromUser(String title, String message, String request) {
        hideProgressDialog();
        AlertDialog.Builder alert = new AlertDialog.Builder(MainActivity.this);
        alert.setCancelable(false);
        LinearLayout llBase = new LinearLayout(MainActivity.this);
        EditText etRequest = new EditText(MainActivity.this);
        alert.setTitle(title);
        alert.setMessage(message);
        etRequest.setBackgroundResource(R.drawable.edittext_background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(30, 10, 30, 10);
        params.height = 500;
        etRequest.setPadding(16, 16, 16, 16);
        etRequest.setVerticalScrollBarEnabled(true);
        etRequest.setGravity(Gravity.START);
        etRequest.setLayoutParams(params);
        switch (request) {
            case Constants.SUBSCRIBER_DETAILS:
                etRequest.setText(PushEngage.SubscriberFields.City + "," + PushEngage.SubscriberFields.Country + ","
                        + PushEngage.SubscriberFields.State + "," +
                        PushEngage.SubscriberFields.Device + "," + PushEngage.SubscriberFields.DeviceType + ","
                        + PushEngage.SubscriberFields.ProfileId + "," + PushEngage.SubscriberFields.Segments
                        + "," + PushEngage.SubscriberFields.Timezone + "," + PushEngage.SubscriberFields.TsCreated);
                break;
            case Constants.ADD_DYNAMIC_SEGMENT:
                etRequest.setText("[\n{\nname: sports,\nduration: 5\n}\n]");
                break;
            case Constants.ADD_ATTRIBUTES:
            case Constants.SET_ATTRIBUTES:
                etRequest.setText("{\nage : 25, \nheight: 6.1\n}");
                break;
            default:
                break;
        }
        llBase.addView(etRequest);
        alert.setView(llBase);
        alert.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
            }
        });
        alert.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String value = etRequest.getText().toString();
                switch (request) {
                    case Constants.SUBSCRIBER_DETAILS:
                        try {
                            List<String> subscriberDetailsList = new ArrayList<String>(Arrays.asList(value.split(",")));
                            showProgressDialog();
                            PushEngage.getSubscriberDetails(subscriberDetailsList, new PushEngageResponseCallback() {
                                @Override
                                public void onSuccess(Object responseObject) {
                                    String response = gson.toJson(responseObject);
                                    showResponse(getString(R.string.subscriber_details), response);
                                    hideProgressDialog();
                                }

                                @Override
                                public void onFailure(Integer errorCode, String errorMessage) {
                                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                    hideProgressDialog();
                                }
                            });

                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;
                    case Constants.ADD_ATTRIBUTES:
                        try {
                            JSONObject jsonObject = new JSONObject(value);
                            PushEngage.addSubscriberAttributes(jsonObject, new PushEngageResponseCallback() {
                                @Override
                                public void onSuccess(Object responseObject) {
                                    Toast.makeText(MainActivity.this, getString(R.string.attributes_added),
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onFailure(Integer errorCode, String errorMessage) {
                                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;

                    case Constants.SET_ATTRIBUTES:
                        try {
                            JSONObject jsonObject = new JSONObject(value);
                            PushEngage.setSubscriberAttributes(jsonObject, new PushEngageResponseCallback() {
                                @Override
                                public void onSuccess(Object responseObject) {
                                    Toast.makeText(MainActivity.this, getString(R.string.attributes_set),
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onFailure(Integer errorCode, String errorMessage) {
                                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                }
                            });
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
                                PushEngage.deleteSubscriberAttributes(attributeList, new PushEngageResponseCallback() {
                                    @Override
                                    public void onSuccess(Object responseObject) {
                                        Toast.makeText(MainActivity.this, getString(R.string.delete_attributes_success),
                                                Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onFailure(Integer errorCode, String errorMessage) {
                                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                    }
                                });
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
                                PushEngage.addSegment(segmentList, new PushEngageResponseCallback() {
                                    @Override
                                    public void onSuccess(Object responseObject) {
                                        Toast.makeText(MainActivity.this, getString(R.string.segment_added),
                                                Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onFailure(Integer errorCode, String errorMessage) {
                                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                    }
                                });
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
                                PushEngage.removeSegment(segmentList, new PushEngageResponseCallback() {
                                    @Override
                                    public void onSuccess(Object responseObject) {
                                        Toast.makeText(MainActivity.this, getString(R.string.delete_segment_success),
                                                Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onFailure(Integer errorCode, String errorMessage) {
                                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                    }
                                });
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
                            PushEngage.addDynamicSegment(segments, new PushEngageResponseCallback() {
                                @Override
                                public void onSuccess(Object responseObject) {
                                    Toast.makeText(MainActivity.this, getString(R.string.dynamic_segment_added),
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onFailure(Integer errorCode, String errorMessage) {
                                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                            e.printStackTrace();
                        }
                        break;
                    case Constants.ADD_PROFILE_ID:
                        if (!TextUtils.isEmpty(value)) {
                            PushEngage.addProfileId(value.replaceAll("\\n", ""), new PushEngageResponseCallback() {
                                @Override
                                public void onSuccess(Object responseObject) {
                                    Toast.makeText(MainActivity.this, getString(R.string.profile_id_added),
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onFailure(Integer errorCode, String errorMessage) {
                                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.invalid_request_format),
                                    Toast.LENGTH_SHORT).show();
                        }
                        break;
                    default:
                        break;
                }

            }
        });
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
            alert.show();
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