package com.pushengage.PushNotificationDemo

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.pushengage.pushengage.helper.PEConstants
import kotlin.system.exitProcess

class SettingsActivity : AppCompatActivity() {

    private lateinit var demoPrefs: DemoPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        demoPrefs = DemoPrefs(this)

        supportActionBar?.title = getString(R.string.settings)
        // Allow back-nav only if SDK is already configured — otherwise force user to complete setup.
        supportActionBar?.setDisplayHomeAsUpEnabled(demoPrefs.isConfigured)

        val etAppId = findViewById<EditText>(R.id.et_app_id)
        val toggleEnv = findViewById<MaterialButtonToggleGroup>(R.id.toggle_env)
        val btnSave = findViewById<MaterialButton>(R.id.btn_save)

        etAppId.setText(demoPrefs.appId)
        toggleEnv.check(
            if (demoPrefs.environment == PEConstants.STG) R.id.btn_env_stg else R.id.btn_env_prod
        )

        btnSave.setOnClickListener {
            val appId = etAppId.text.toString().trim()
            if (appId.isEmpty()) {
                Toast.makeText(this, R.string.settings_app_id_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val env = if (toggleEnv.checkedButtonId == R.id.btn_env_stg) PEConstants.STG else PEConstants.PROD
            demoPrefs.appId = appId
            demoPrefs.environment = env
            promptRestart()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        // Block back when SDK isn't configured — saving is the only valid exit.
        if (demoPrefs.isConfigured) {
            super.onBackPressed()
        }
    }

    private fun promptRestart() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_restart_title)
            .setMessage(R.string.settings_restart_message)
            .setCancelable(false)
            .setPositiveButton(R.string.settings_restart_now) { _, _ -> restartApp() }
            .show()
    }

    private fun restartApp() {
        val launchIntent = Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(launchIntent)
        finish()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
