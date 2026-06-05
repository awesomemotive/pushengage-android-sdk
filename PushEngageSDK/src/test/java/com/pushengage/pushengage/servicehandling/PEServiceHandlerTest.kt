package com.pushengage.pushengage.servicehandling

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.ChannelEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import com.pushengage.pushengage.model.request.FetchRequest
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest
import com.pushengage.pushengage.model.response.ChannelResponseModel
import com.pushengage.pushengage.model.response.FetchResponse
import com.pushengage.pushengage.model.response.NetworkResponse
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.Timer
import java.util.TimerTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEServiceHandlerTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var daoInterface: DaoInterface
    private lateinit var handler: PEServiceHandler
    private lateinit var restClientStatic: MockedStatic<RestClient>
    private lateinit var backendClient: RestClient.RTApiInterface
    private lateinit var backendCdnClient: RestClient.RTApiInterface

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        prefs = PEPrefs(context)
        prefs.siteKey = "site_key_test"
        prefs.hash = "hash_test"
        prefs.siteId = 1001L
        prefs.setIsNotificationDisabled(0L)

        daoInterface = mock(DaoInterface::class.java)
        handler = PEServiceHandler(context, prefs, com.google.gson.Gson(), daoInterface)

        backendClient = mock(RestClient.RTApiInterface::class.java)
        backendCdnClient = mock(RestClient.RTApiInterface::class.java)

        restClientStatic = Mockito.mockStatic(RestClient::class.java)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendClient(context) }
            .thenReturn(backendClient)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendCdnClient(context) }
            .thenReturn(backendCdnClient)
    }

    @After
    fun tearDown() {
        restClientStatic.close()
    }

    @Test
    fun getChannelInfo_success_insertsChannelAndReturnsNotDefault() {
        val channelResponse = ChannelResponseModel(
            errorCode = 0L,
            data = ChannelResponseModel.Data(
                channelId = 22L,
                groupId = 11L,
                channelName = "Cart Updates",
                channelDescription = "Cart activity",
                options = ChannelResponseModel.Options(
                    importance = "IMPORTANCE_DEFAULT",
                    sound = "DEFAULT",
                    vibration = "DEFAULT",
                    lockScreen = "VISIBILITY_PUBLIC",
                    badges = true
                ),
                siteId = 1001L,
                groupName = "Default Group"
            )
        )

        Mockito.`when`(backendCdnClient.getChannelInfo("site_key_test", "channel_abc"))
            .thenReturn(ImmediateCall(response = Response.success(channelResponse)))

        var completionDefaultValue: Boolean? = null
        handler.getChannelInfo(
            channelId = "channel_abc",
            notificationManager = mock(NotificationManager::class.java),
            notificationBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID),
            payload = FCMPayloadModel(notificationId = 1),
            completion = { isDefault ->
                completionDefaultValue = isDefault
            }
        )

        assertEquals(false, completionDefaultValue)
        val channelCaptor = argumentCaptor<ChannelEntity>()
        verify(daoInterface, times(1)).insert(channelCaptor.capture())
        assertEquals("22", channelCaptor.firstValue.channelId)
        assertEquals("Cart Updates", channelCaptor.firstValue.channelName)
    }

    @Test
    fun getChannelInfo_httpFailure_returnsDefaultWithoutInsert() {
        Mockito.`when`(backendCdnClient.getChannelInfo("site_key_test", "channel_abc"))
            .thenReturn(
                ImmediateCall(
                    response = Response.error(
                        500,
                        ResponseBody.create(null, "server_error")
                    )
                )
            )

        var completionDefaultValue: Boolean? = null
        handler.getChannelInfo(
            channelId = "channel_abc",
            notificationManager = mock(NotificationManager::class.java),
            notificationBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID),
            payload = FCMPayloadModel(notificationId = 1),
            completion = { isDefault ->
                completionDefaultValue = isDefault
            }
        )

        assertEquals(true, completionDefaultValue)
        verify(daoInterface, never()).insert(Mockito.any(ChannelEntity::class.java))
    }

    @Test
    fun updateSubscriberStatus_success_updatesPreferenceState() {
        val request = UpdateSubscriberStatusRequest(1001L, "old_hash", 1L, false)
        Mockito.`when`(backendClient.updateSubscriberStatus(request))
            .thenReturn(ImmediateCall(response = Response.success(NetworkResponse())))

        handler.updateSubscriberStatus(request)

        assertEquals(1L, prefs.isNotificationDisabled())
    }

    @Test
    fun updateSubscriberStatus_networkFailure_keepsPreferenceUnchanged() {
        prefs.setIsNotificationDisabled(0L)
        val request = UpdateSubscriberStatusRequest(1001L, "old_hash", 1L, false)
        Mockito.`when`(backendClient.updateSubscriberStatus(request))
            .thenReturn(ImmediateCall(failure = IOException("network down")))

        handler.updateSubscriberStatus(request)

        assertEquals(0L, prefs.isNotificationDisabled())
    }

    @Test
    fun getSponsoredNotificationInfo_http500_schedulesRetry() {
        Mockito.`when`(backendClient.fetch(Mockito.any(FetchRequest::class.java)))
            .thenReturn(
                ImmediateCall(
                    response = Response.error(
                        500,
                        ResponseBody.create(null, "server_error")
                    )
                )
            )

        var callbackInvoked = false
        val timerConstruction: MockedConstruction<Timer> = Mockito.mockConstruction(Timer::class.java)
        try {
            handler.getSponsoredNotificationInfo(
                fetchRequest = FetchRequest("tag_x", null),
                channelId = "channel_x",
                id = 7,
                isRetry = false,
                sendNotification = { _, _ ->
                    callbackInvoked = true
                }
            )
        } finally {
            // Verify before closing the construction scope.
            assertFalse(callbackInvoked)
            assertEquals(1, timerConstruction.constructed().size)
            verify(timerConstruction.constructed()[0], times(1))
                .schedule(Mockito.any(TimerTask::class.java), Mockito.eq(PEConstants.RETRY_DELAY.toLong()))
            timerConstruction.close()
        }
    }

    @Test
    fun getSponsoredNotificationInfo_http404_doesNotScheduleRetry() {
        Mockito.`when`(backendClient.fetch(Mockito.any(FetchRequest::class.java)))
            .thenReturn(
                ImmediateCall(
                    response = Response.error(
                        404,
                        ResponseBody.create(null, "not_found")
                    )
                )
            )

        val timerConstruction: MockedConstruction<Timer> = Mockito.mockConstruction(Timer::class.java)
        try {
            handler.getSponsoredNotificationInfo(
                fetchRequest = FetchRequest("tag_404", null),
                channelId = "channel_404",
                id = 9,
                isRetry = false,
                sendNotification = { _, _ -> }
            )
            assertTrue(timerConstruction.constructed().isEmpty())
        } finally {
            timerConstruction.close()
        }
    }

    private class ImmediateCall<T>(
        private val response: Response<T>? = null,
        private val failure: Throwable? = null
    ) : Call<T> {
        private var canceled = false

        override fun clone(): Call<T> = ImmediateCall(response, failure)

        override fun execute(): Response<T> {
            if (failure != null) {
                throw IOException(failure)
            }
            if (response != null) {
                return response
            }
            throw IllegalStateException("No response configured")
        }

        override fun enqueue(callback: Callback<T>) {
            if (failure != null) {
                callback.onFailure(this, failure)
                return
            }
            if (response != null) {
                callback.onResponse(this, response)
                return
            }
            callback.onFailure(this, IllegalStateException("No response configured"))
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
