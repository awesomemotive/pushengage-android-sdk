package com.pushengage.PushNotificationDemo.TriggerCampaign

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pushengage.PushNotificationDemo.MainActivity
import com.pushengage.PushNotificationDemo.R
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.PushEngage
import java.lang.reflect.Type


class TriggerCampaignActivity : AppCompatActivity() {
    private lateinit var sendTriggerEventButton: Button
    private lateinit var addAlertButton: Button
    private lateinit var enableAutomatedNotification: Button
    private lateinit var disableAutomatedNotification: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trigger_campaign)
        supportActionBar?.setDisplayHomeAsUpEnabled(true);
        title = "Trigger Campaigns"
        val intent = intent
        val data = intent.data
        if (data != null) {
            data.getQueryParameter("key1")?.let { Log.d("DeeplinkData", it) }
        }

        val dt = intent.getStringExtra("data")
        val gson = Gson()
        val type: Type = object : TypeToken<Map<String?, String?>?>() {}.type
        val jsonData = gson.fromJson<Map<String, String>>(dt, type)
        setup()
    }

    private fun setup() {
        sendTriggerEventButton = findViewById(R.id.sendTriggerEventButton)
        addAlertButton = findViewById(R.id.addAlertButton)
        enableAutomatedNotification = findViewById(R.id.enableTriggerButton)
        disableAutomatedNotification = findViewById(R.id.disableTriggerButton)

        sendTriggerEventButton.setOnClickListener {
            val intent = Intent(
                this@TriggerCampaignActivity,
                TriggerEntryActivity::class.java
            )
            startActivity(intent)
        }

        addAlertButton.setOnClickListener {
            val intent = Intent(this@TriggerCampaignActivity, AddAlertActivity::class.java)
            startActivity(intent)
        }

        enableAutomatedNotification.setOnClickListener {
            PushEngage.automatedNotification(PushEngage.TriggerStatusType.enabled, object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {
                    Toast.makeText(this@TriggerCampaignActivity, "Trigger Enabled successfully", Toast.LENGTH_LONG).show()
                }

                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    Toast.makeText(this@TriggerCampaignActivity, "Trigger Enabled failed", Toast.LENGTH_LONG).show()
                }

            })
        }

        disableAutomatedNotification.setOnClickListener {
            PushEngage.automatedNotification(PushEngage.TriggerStatusType.disabled, object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {
                    Toast.makeText(this@TriggerCampaignActivity, "Trigger Disabled successfully", Toast.LENGTH_LONG).show()
                }

                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    Toast.makeText(this@TriggerCampaignActivity, "Trigger Disabled failed", Toast.LENGTH_LONG).show()
                }

            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            android.R.id.home -> {
                onBackPressed();
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        this.finish()
    }
}