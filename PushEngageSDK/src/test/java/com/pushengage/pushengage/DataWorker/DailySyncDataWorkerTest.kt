package com.pushengage.pushengage.DataWorker

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest
import com.pushengage.pushengage.model.response.NetworkResponse
import okhttp3.Request
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DailySyncDataWorkerTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var worker: DailySyncDataWorker
    private lateinit var backendClient: RestClient.RTApiInterface
    private lateinit var restClientStatic: MockedStatic<RestClient>
    private lateinit var notificationManagerCompatStatic: MockedStatic<NotificationManagerCompat>
    private lateinit var pushEngageStatic: MockedStatic<PushEngage>
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)
        prefs.siteKey = "daily_site_key"
        prefs.siteId = 101L
        prefs.hash = "daily_hash"
        prefs.setDeleteOnNotificationDisable(false)
        prefs.setIsManuallyUnsubscribed(false)
        prefs.setIsSubscriberDeleted(false)
        prefs.setIsNotificationDisabled(0L)

        worker = DailySyncDataWorker(context, mock(WorkerParameters::class.java))

        backendClient = mock(RestClient.RTApiInterface::class.java)
        restClientStatic = Mockito.mockStatic(RestClient::class.java)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendClient(context) }
            .thenReturn(backendClient)

        notificationManagerCompat = mock(NotificationManagerCompat::class.java)
        notificationManagerCompatStatic = Mockito.mockStatic(NotificationManagerCompat::class.java)
        notificationManagerCompatStatic.`when`<NotificationManagerCompat> { NotificationManagerCompat.from(context) }
            .thenReturn(notificationManagerCompat)

        pushEngageStatic = Mockito.mockStatic(PushEngage::class.java)
    }

    @After
    fun tearDown() {
        pushEngageStatic.close()
        notificationManagerCompatStatic.close()
        restClientStatic.close()
    }

    @Test
    fun doWork_whenNotificationStateUnchanged_returnsSuccessWithoutServerCalls() {
        Mockito.`when`(notificationManagerCompat.areNotificationsEnabled()).thenReturn(true)
        prefs.setIsNotificationDisabled(0L)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(backendClient, never()).updateSubscriberStatus(Mockito.any(UpdateSubscriberStatusRequest::class.java))
        pushEngageStatic.verifyNoInteractions()
    }

    @Test
    fun doWork_whenNotificationsDisabled_updatesSubscriberStatusAndPrefs() {
        Mockito.`when`(notificationManagerCompat.areNotificationsEnabled()).thenReturn(false)
        prefs.setIsNotificationDisabled(0L)
        prefs.setIsManuallyUnsubscribed(false)
        prefs.setIsSubscriberDeleted(false)

        Mockito.`when`(backendClient.updateSubscriberStatus(Mockito.any(UpdateSubscriberStatusRequest::class.java)))
            .thenReturn(ImmediateCall(response = Response.success(NetworkResponse())))

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(backendClient, times(1)).updateSubscriberStatus(Mockito.any(UpdateSubscriberStatusRequest::class.java))
        assertEquals(1L, prefs.isNotificationDisabled())
    }

    @Test
    fun doWork_whenSiteKeyEmpty_skipsApiCallAndReturnsSuccess() {
        // Issue #38 parity with WeeklySyncDataWorker: misconfigured installs must not
        // hit the backend daily with bogus siteId/hash and generate 400s forever.
        prefs.siteKey = ""

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(backendClient, never()).updateSubscriberStatus(Mockito.any(UpdateSubscriberStatusRequest::class.java))
        pushEngageStatic.verifyNoInteractions()
    }

    @Test
    fun doWork_whenSiteKeyBlank_skipsApiCallAndReturnsSuccess() {
        prefs.siteKey = "   "

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(backendClient, never()).updateSubscriberStatus(Mockito.any(UpdateSubscriberStatusRequest::class.java))
        pushEngageStatic.verifyNoInteractions()
    }

    @Test
    fun doWork_whenEnabledAndSubscriberDeleted_callsAddSubscriberApi() {
        Mockito.`when`(notificationManagerCompat.areNotificationsEnabled()).thenReturn(true)
        prefs.setIsNotificationDisabled(1L)
        prefs.setIsSubscriberDeleted(true)
        prefs.setIsManuallyUnsubscribed(false)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        pushEngageStatic.verify({ PushEngage.callAddSubscriberAPI() }, times(1))
        verify(backendClient, never()).updateSubscriberStatus(Mockito.any(UpdateSubscriberStatusRequest::class.java))
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
