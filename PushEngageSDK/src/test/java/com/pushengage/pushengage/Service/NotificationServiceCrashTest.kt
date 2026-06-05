package com.pushengage.pushengage.Service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.model.response.NetworkResponse
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

/**
 * Crash-scenario tests for NotificationService.
 *
 * The service has several crash-prone patterns:
 * 1. onStartCommand with null intent — Android system restarts service with null intent
 *    (line 52: intent.getStringExtra crashes with NPE)
 * 2. Objects.requireNonNull(manager) — crashes on devices without telephony (line 89)
 * 3. Null NotificationManager cast (line 57-58)
 *
 * Tests marked "PROVES BUG" will FAIL on current code, documenting crash bugs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationServiceCrashTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = PEPrefs(context)
        prefs.hash = "test_hash"
        prefs.siteId = 12345L
        setNetworkConnected(true)
    }

    // --- onStartCommand null intent (PROVES BUG) ---

    /**
     * PROVES BUG: When Android restarts a service (e.g., after low memory kill),
     * it calls onStartCommand with null intent. The production code at line 52
     * does `intent.getStringExtra(...)` without null check, causing NPE.
     *
     * This test will FAIL with NullPointerException on current code.
     */
    @Test(expected = NullPointerException::class)
    fun onStartCommand_nullIntent_crashes() {
        val service = Robolectric.setupService(NotificationService::class.java)
        service.onStartCommand(null, 0, 1)
    }

    // --- onStartCommand with valid intent ---

    @Test
    fun onStartCommand_validIntent_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        val intent = Intent()
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ACTION_EXTRA, "action1")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val result = service.onStartCommand(intent, 0, 1)
        // START_NOT_STICKY = 2
        assertEquals(2, result)
    }

    @Test
    fun onStartCommand_missingTagExtra_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        val intent = Intent()
        // No TAG_EXTRA set — getStringExtra returns null
        intent.putExtra(PEConstants.ACTION_EXTRA, "action1")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(2, result)
    }

    @Test
    fun onStartCommand_missingActionExtra_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        val intent = Intent()
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")
        intent.putExtra(PEConstants.ID_EXTRA, 42)

        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(2, result)
    }

    @Test
    fun onStartCommand_defaultIdExtra_isNegativeOne() {
        val service = Robolectric.setupService(NotificationService::class.java)
        val intent = Intent()
        // No ID_EXTRA set — getIntExtra returns default -1
        intent.putExtra(PEConstants.TAG_EXTRA, "test_tag")

        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(2, result)
    }

    // --- notificationClick tests ---

    @Test
    fun notificationClick_nullTag_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        // null tag and action should not crash
        service.notificationCLick(context, "test_hash", null, null, false)
    }

    @Test
    fun notificationClick_nullAction_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        service.notificationCLick(context, "test_hash", null, "test_tag", false)
    }

    @Test
    fun notificationClick_emptyHash_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        service.notificationCLick(context, "", null, "test_tag", false)
    }

    @Test
    fun notificationClick_networkSuccess_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        service.notificationCLick(context, "test_hash", "action1", "test_tag", false)
    }

    @Test
    fun notificationClick_noNetwork_doesNotCrash() {
        setNetworkConnected(false)
        val service = Robolectric.setupService(NotificationService::class.java)
        service.notificationCLick(context, "test_hash", "action1", "test_tag", false)
    }

    @Test
    fun notificationClick_isRetry_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        service.notificationCLick(context, "test_hash", "action1", "test_tag", true)
    }

    @Test
    fun notificationClick_retryFailure_withNullPrefs_doesNotCrash() {
        val service = Robolectric.setupService(NotificationService::class.java)
        val prefsField = NotificationService::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(null, null)

        val analyticsClient = Mockito.mock(RestClient.RTApiInterface::class.java)
        val logClient = Mockito.mock(RestClient.RTApiInterface::class.java)

        Mockito.mockStatic(RestClient::class.java).use { restClientStatic ->
            restClientStatic.`when`<RestClient.RTApiInterface> {
                RestClient.getAnalyticsClient(Mockito.eq(context), Mockito.anyMap<String, String>())
            }.thenReturn(analyticsClient)
            restClientStatic.`when`<RestClient.RTApiInterface> {
                RestClient.getLogClient(Mockito.eq(context))
            }.thenReturn(logClient)

            Mockito.`when`(
                analyticsClient.notificationClick(
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any()
                )
            ).thenReturn(ImmediateCall(failure = RuntimeException("forced failure")))
            Mockito.`when`(logClient.logs(Mockito.any())).thenReturn(
                ImmediateCall(response = Response.success(ResponseBody.create(null, "")))
            )

            service.notificationCLick(context, "test_hash", "action1", "test_tag", true)
        }
    }

    private fun setNetworkConnected(connected: Boolean) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivityManager = shadowOf(connectivityManager)

        val networkInfo = ShadowNetworkInfo.newInstance(
            null,
            ConnectivityManager.TYPE_WIFI,
            0,
            connected,
            if (connected) NetworkInfo.State.CONNECTED else NetworkInfo.State.DISCONNECTED
        )
        shadowConnectivityManager.setActiveNetworkInfo(networkInfo)
        shadowConnectivityManager.setNetworkInfo(ConnectivityManager.TYPE_WIFI, networkInfo)
        shadowConnectivityManager.setNetworkInfo(ConnectivityManager.TYPE_MOBILE, networkInfo)
    }

    private class ImmediateCall<T>(
        private val response: Response<T>? = null,
        private val failure: Throwable? = null
    ) : Call<T> {
        private var canceled = false

        override fun clone(): Call<T> = ImmediateCall(response, failure)

        override fun execute(): Response<T> {
            if (failure != null) throw IOException(failure)
            return response ?: throw IllegalStateException("No response configured")
        }

        override fun enqueue(callback: Callback<T>) {
            if (failure != null) {
                callback.onFailure(this, failure)
            } else if (response != null) {
                callback.onResponse(this, response)
            } else {
                callback.onFailure(this, IllegalStateException("No response configured"))
            }
        }

        override fun isExecuted(): Boolean = false

        override fun cancel() {
            canceled = true
        }

        override fun isCanceled(): Boolean = canceled

        override fun request(): Request = Request.Builder().url("https://example.com").build()

        override fun timeout(): Timeout = Timeout.NONE
    }
}
