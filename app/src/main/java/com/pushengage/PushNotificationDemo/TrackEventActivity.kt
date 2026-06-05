package com.pushengage.PushNotificationDemo

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.model.request.TrackEvent
import org.json.JSONObject

class TrackEventActivity : AppCompatActivity() {

    private lateinit var eventNameInput: EditText
    private lateinit var providerInput: EditText
    private lateinit var eventTypeInput: EditText
    private lateinit var profileIdInput: EditText
    private lateinit var dataInput: EditText
    private lateinit var trackEventButton: MaterialButton
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_event)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        eventNameInput = findViewById(R.id.eventNameInput)
        providerInput = findViewById(R.id.providerInput)
        eventTypeInput = findViewById(R.id.eventTypeInput)
        profileIdInput = findViewById(R.id.profileIdInput)
        dataInput = findViewById(R.id.dataInput)
        trackEventButton = findViewById(R.id.trackEventButton)
        progressBar = findViewById(R.id.progressBar)

        trackEventButton.setOnClickListener {
            progressBar.visibility = View.VISIBLE
            trackEventButton.isEnabled = false
            didClickTrackEvent()
        }
    }

    private fun didClickTrackEvent() {
        val eventName = eventNameInput.text.toString().trim()
        if (eventName.isEmpty()) {
            Toast.makeText(this, "Event name is required", Toast.LENGTH_SHORT).show()
            progressBar.visibility = View.GONE
            trackEventButton.isEnabled = true
            return
        }

        val provider = providerInput.text.toString().trim().ifEmpty { null }
        val eventType = eventTypeInput.text.toString().trim().ifEmpty { null }
        val profileId = profileIdInput.text.toString().trim().ifEmpty { null }
        val data = parseDataJson(dataInput.text.toString().trim())

        val event = TrackEvent(
            eventName = eventName,
            provider = provider,
            eventType = eventType,
            profileId = profileId,
            data = data
        )

        PushEngage.trackEvent(event, loggingCallback(this, "trackEvent",
            onSuccess = {
                progressBar.visibility = View.GONE
                trackEventButton.isEnabled = true
            },
            onFailure = { _, _ ->
                progressBar.visibility = View.GONE
                trackEventButton.isEnabled = true
            }
        ))
    }

    private fun parseDataJson(input: String): Map<String, Any>? {
        if (input.isEmpty()) return null
        return try {
            val obj = JSONObject(input)
            val map = mutableMapOf<String, Any>()
            obj.keys().forEach { key -> map[key] = obj.get(key) }
            map
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid JSON in Data — sending event without it", Toast.LENGTH_SHORT).show()
            null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
