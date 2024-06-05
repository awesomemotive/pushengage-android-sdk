package com.pushengage.pushengage.notificationhandling

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.pushengage.pushengage.Service.NotificationService
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PELogger


internal class PENotificationHandlerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent()
    }

    /**
     * Handle navigation based on intent data
     */
    private fun handleIntent() {
        val tag = intent.getStringExtra(PEConstants.TAG_EXTRA)
        val url = intent.getStringExtra(PEConstants.URL_EXTRA)
        val data = intent.getSerializableExtra(PEConstants.DATA_EXTRA) as HashMap<String, String>?
        val action = intent.getStringExtra(PEConstants.ACTION_EXTRA)
        val id = intent.getIntExtra(PEConstants.ID_EXTRA, -1)

        val serviceIntent = Intent(this, NotificationService::class.java)
        serviceIntent.putExtra(PEConstants.TAG_EXTRA, tag)
        serviceIntent.putExtra(PEConstants.ID_EXTRA, id)
        serviceIntent.putExtra(PEConstants.ACTION_EXTRA, action)
        serviceIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        startService(serviceIntent)

        if (!TextUtils.isEmpty(url)) {
            try {
                val i = Intent(Intent.ACTION_VIEW)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                i.data = Uri.parse(url)
                val gson = Gson()
                val data = gson.toJson(data)
                i.putExtra(PEConstants.DATA_EXTRA, data)
                startActivity(i)
            } catch (e: Exception) {
                PELogger.error("Handle navigation",e)
                launchMainPackage(data, url)
            }
        } else {
            //deepLink or url not available, app is launched with additional data. User can handle navigation in their launch screen.
            launchMainPackage(data, url)
        }
        finish()
    }

    /**
     * Launch main package
     * @param data additionalData received in payload
     * @param url Deeplink/Notification url received in payload
     */
    private fun launchMainPackage(data: HashMap<String, String>?, url: String?) {
        try {
            val packageName = packageName
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(packageName)
            intent?.putExtra(PEConstants.DATA_EXTRA, data)
            intent?.putExtra(PEConstants.URL_EXTRA, url)
            applicationContext.startActivity(intent)
        } catch (e: Exception) {
            PELogger.error("Launch main package", e)
        }
    }

}