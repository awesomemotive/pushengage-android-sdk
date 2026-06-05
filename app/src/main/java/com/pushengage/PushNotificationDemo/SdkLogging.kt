package com.pushengage.PushNotificationDemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback

private val gson by lazy { Gson() }
private val prettyGson by lazy { GsonBuilder().setPrettyPrinting().create() }

/**
 * Wraps a PushEngageResponseCallback so success/failure both flow into SdkEventLog
 * AND auto-open a BottomSheet showing the actual SDK response/error.
 * The optional lambdas run after for any extra UI state cleanup (hide progress, re-enable button).
 */
fun loggingCallback(
    activity: AppCompatActivity,
    tag: String,
    onSuccess: ((Any?) -> Unit)? = null,
    onFailure: ((Int?, String?) -> Unit)? = null
): PushEngageResponseCallback = object : PushEngageResponseCallback {
    override fun onSuccess(responseObject: Any?) {
        val preview = (responseObject?.let { gson.toJson(it) } ?: "(no body)")
            .let { if (it.length > 120) it.substring(0, 117) + "…" else it }
        SdkEventLog.get().success(tag, preview)
        showSdkResponseSheet(activity, "$tag — success", responseObject)
        onSuccess?.invoke(responseObject)
    }

    override fun onFailure(errorCode: Int?, errorMessage: String?) {
        SdkEventLog.get().failure(tag, "($errorCode) $errorMessage")
        val body = "Error code: $errorCode\n\n${errorMessage ?: "(no message)"}"
        showSdkResponseSheet(activity, "$tag — failed", body)
        onFailure?.invoke(errorCode, errorMessage)
    }
}

fun showSdkResponseSheet(activity: AppCompatActivity, title: String, payload: Any?) {
    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

    val body: String = when (payload) {
        null -> "Operation completed successfully."
        is String -> if (payload.isEmpty())
            "Operation completed successfully."
        else tryPrettyPrintJson(payload)
        else -> prettyGson.toJson(payload)
    }

    val sheet = BottomSheetDialog(activity)
    val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_response, null)
    content.findViewById<TextView>(R.id.tv_response_title).text = title
    content.findViewById<TextView>(R.id.tv_response_body).text = body
    content.findViewById<View>(R.id.btn_response_copy).setOnClickListener {
        copyToClipboard(activity, title, body)
    }
    content.findViewById<View>(R.id.btn_response_close).setOnClickListener { sheet.dismiss() }
    sheet.setContentView(content)
    sheet.show()
}

private fun tryPrettyPrintJson(raw: String): String {
    if (raw.isEmpty()) return ""
    return try {
        val el = prettyGson.fromJson(raw, JsonElement::class.java)
        if (el != null && (el.isJsonObject || el.isJsonArray)) prettyGson.toJson(el) else raw
    } catch (_: Exception) {
        raw
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    if (value.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
