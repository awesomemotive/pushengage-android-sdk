package com.pushengage.PushNotificationDemo.TriggerCampaign

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.pushengage.PushNotificationDemo.R
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.model.request.TriggerCampaign

data class CustomData(val keyText: String, val valueText: String, val cellState: DataCellState)

class TriggerEntryActivity : AppCompatActivity() {

    private lateinit var campaignName: TextInputEditText
    private lateinit var eventName: TextInputEditText
    private lateinit var referenceId: TextInputEditText
    private lateinit var sendTrigger: Button
    private lateinit var profileId: TextInputEditText

    private lateinit var adapter: CustomAdapter
    private val dataList: ArrayList<CustomData> = ArrayList()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trigger_entry)
        setup()
    }

    private fun setup() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        supportActionBar?.setDisplayHomeAsUpEnabled(true);
        title = "Trigger Campaign"
        campaignName = findViewById(R.id.campaignName)
        eventName = findViewById(R.id.eventName)
        referenceId = findViewById(R.id.referenceId)
        sendTrigger = findViewById(R.id.sendTriggerButton)
        profileId = findViewById(R.id.profileId)

        sendTrigger.setOnClickListener {
            var dataMap: MutableMap<String, String> = mutableMapOf()
            dataList.filter { it.keyText.isNotEmpty() }.forEach { dataMap[it.keyText] = it.valueText }
            val triggerCampaign = TriggerCampaign(
                campaignName.text.toString(),
                eventName.text.toString(),
                referenceId.text.toString().takeIf { it.isNotEmpty() },
                profileId.text.toString().takeIf { it.isNotEmpty() },
                dataMap)
            PushEngage.sendTriggerEvent(triggerCampaign, object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {
                    Toast.makeText(this@TriggerEntryActivity,"Send Trigger Alert Successfully", Toast.LENGTH_LONG).show()
                }

                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    Toast.makeText(this@TriggerEntryActivity,errorMessage.toString(), Toast.LENGTH_LONG).show()
                }

            })
        }

        dataList.add(CustomData("","", DataCellState.add))
        adapter = CustomAdapter(this, dataList)

        val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
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
        this.finish()
    }
}