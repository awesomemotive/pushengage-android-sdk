package com.pushengage.PushNotificationDemo

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pushengage.pushengage.PushEngage

class ComposeTestActivity : ComponentActivity() {
    
    private lateinit var statusTextView: TextView
    private lateinit var resultTextView: TextView
    private lateinit var requestButton: Button
    private lateinit var refreshButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set up modern back navigation for ComponentActivity
        setupBackNavigation()
        
        // Create UI programmatically 
        setupUI()
        
        // Update initial permission status
        updatePermissionStatus()
    }
    
    private fun setupBackNavigation() {
        // Modern way to handle back navigation in ComponentActivity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }
    
    private fun setupUI() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48) // left, top, right, bottom
        }
        
        // Custom Toolbar/Header with Back Button (since ComponentActivity doesn't have ActionBar)
        val toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32) // left, top, right, bottom
        }
        
        val backButton = Button(this).apply {
            text = "← Back"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 16
            }
            setOnClickListener { 
                onBackPressedDispatcher.onBackPressed()
            }
        }
        toolbarContainer.addView(backButton)
        
        val titleText = TextView(this).apply {
            text = "ComponentActivity Test"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        toolbarContainer.addView(titleText)
        
        container.addView(toolbarContainer)
        
        // Header
        val headerText = TextView(this).apply {
            text = "ComponentActivity Permission Test"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24) // left, top, right, bottom
        }
        container.addView(headerText)
        
        // Current Status Section
        val statusLabel = TextView(this).apply {
            text = "Current Permission Status:"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16) // left, top, right, bottom
        }
        container.addView(statusLabel)
        
        statusTextView = TextView(this).apply {
            text = "Checking..."
            textSize = 16f
            setPadding(32, 0, 0, 16) // left, top, right, bottom
        }
        container.addView(statusTextView)
        
        refreshButton = Button(this).apply {
            text = "🔄 Refresh Status"
            setOnClickListener { updatePermissionStatus() }
        }
        container.addView(refreshButton)
        
        // Permission Request Section
        val requestLabel = TextView(this).apply {
            text = "🔔 Test Permission Request:"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 16) // left, top, right, bottom
        }
        container.addView(requestLabel)
        
        requestButton = Button(this).apply {
            text = "Request Permission"
            setOnClickListener { requestPermission() }
        }
        container.addView(requestButton)
        
        // Result Section
        val resultLabel = TextView(this).apply {
            text = "📋 Last Request Result:"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 16) // left, top, right, bottom
        }
        container.addView(resultLabel)
        
        resultTextView = TextView(this).apply {
            text = "No request made yet"
            textSize = 14f
            setPadding(32, 0, 0, 16) // left, top, right, bottom
        }
        container.addView(resultTextView)
        
        setContentView(container)
    }
    
    private fun updatePermissionStatus() {
        val status = PushEngage.getNotificationPermissionStatus()
        statusTextView.text = status.uppercase()
        
        // Color coding
        when (status) {
            "granted" -> statusTextView.setTextColor(android.graphics.Color.GREEN)
            "denied" -> statusTextView.setTextColor(android.graphics.Color.RED)
            else -> statusTextView.setTextColor(android.graphics.Color.GRAY)
        }
        
        Log.d("ComponentTest", "Current permission status: $status")
    }
    
    private fun requestPermission() {
        requestButton.isEnabled = false
        requestButton.text = "Requesting..."
        resultTextView.text = "Requesting permission..."
        
        // Add detailed debugging information
        val androidVersion = android.os.Build.VERSION.SDK_INT
        val currentStatus = PushEngage.getNotificationPermissionStatus()
        
        PushEngage.requestNotificationPermission(
            this
        ) { granted, error ->
            requestButton.isEnabled = true
            requestButton.text = "Request Permission"

            val result = if (granted) {
                "Permission GRANTED!"
            } else {
                "Permission DENIED\nError: ${error?.message ?: "Unknown error"}"
            }

            resultTextView.text = result

            // Update current status
            updatePermissionStatus()

            Log.d("ComponentTest", "ComponentActivity test completed")
        }
    }
} 