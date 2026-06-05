package com.pushengage.pushengage.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.pushengage.pushengage.Database.ChannelEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.model.response.ChannelResponseModel
import com.pushengage.pushengage.model.response.FetchResponse
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Verifies that channel-fetch API call (issue #39) only fires when the FCM payload includes a
 * non-empty channelId. When the backend omits channelId, the SDK must short-circuit to the OS
 * default channel without any network round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEFirebaseMessagingServiceChannelTest {

    private lateinit var context: Context
    private lateinit var service: PEFirebaseMessagingService
    private lateinit var daoInterface: DaoInterface
    private lateinit var roomDatabase: PERoomDatabase
    private lateinit var roomDbStatic: MockedStatic<PERoomDatabase>
    private lateinit var restClientStatic: MockedStatic<RestClient>
    private lateinit var backendClient: RestClient.RTApiInterface
    private lateinit var cdnClient: RestClient.RTApiInterface
    private lateinit var analyticsClient: RestClient.RTApiInterface

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        service = Robolectric.setupService(PEFirebaseMessagingService::class.java)
        daoInterface = mock(DaoInterface::class.java)
        roomDatabase = mock(PERoomDatabase::class.java)
        backendClient = mock(RestClient.RTApiInterface::class.java)
        cdnClient = mock(RestClient.RTApiInterface::class.java)
        analyticsClient = mock(RestClient.RTApiInterface::class.java)

        roomDbStatic = Mockito.mockStatic(PERoomDatabase::class.java)
        roomDbStatic.`when`<PERoomDatabase> { PERoomDatabase.getDatabase(context) }.thenReturn(roomDatabase)
        Mockito.`when`(roomDatabase.daoInterface()).thenReturn(daoInterface)

        restClientStatic = Mockito.mockStatic(RestClient::class.java)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendClient(any()) }
            .thenReturn(backendClient)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendCdnClient(any()) }
            .thenReturn(cdnClient)
        restClientStatic.`when`<RestClient.RTApiInterface> {
            RestClient.getAnalyticsClient(any(), ArgumentMatchers.anyMap<String, String>())
        }.thenReturn(analyticsClient)

        // Track-view call is best-effort; return a no-op call so it doesn't NPE if invoked.
        Mockito.`when`(
            analyticsClient.notificationView(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()
            )
        ).thenReturn(ImmediateCall(failure = RuntimeException("ignored")))
        Mockito.`when`(cdnClient.getChannelInfo(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(ImmediateCall(failure = RuntimeException("ignored")))
    }

    @After
    fun tearDown() {
        restClientStatic.close()
        roomDbStatic.close()
    }

    /**
     * Backend omitted channelId → SDK must NOT call /notification-channels/{id}.
     * This is the bug from issue #39.
     */
    @Test
    fun onMessageReceived_payloadWithoutChannelId_doesNotFetchChannelInfo() {
        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-no-channel")
            .setData(mapOf(
                "t" to "Title",
                "b" to "Body",
                "id" to "1001"
                // no "ci" field
            ))
            .build()

        service.onMessageReceived(remoteMessage)

        verify(cdnClient, never()).getChannelInfo(ArgumentMatchers.any(), ArgumentMatchers.any())
    }

    /**
     * Backend supplied a real channelId and it isn't cached locally → fetch must fire.
     */
    @Test
    fun onMessageReceived_payloadWithRealChannelId_fetchesChannelInfo() {
        Mockito.`when`(daoInterface.getChannel("real_channel_42")).thenReturn(null)

        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-real-channel")
            .setData(mapOf(
                "t" to "Title",
                "b" to "Body",
                "id" to "1002",
                "ci" to "real_channel_42"
            ))
            .build()

        service.onMessageReceived(remoteMessage)

        verify(cdnClient, times(1))
            .getChannelInfo(ArgumentMatchers.any(), Mockito.eq("real_channel_42"))
    }

    /**
     * Backend supplied a channelId that happens to equal the local sentinel "Default Channel"
     * (e.g., a user named a dashboard channel "Default Channel"). The SDK must still fetch it —
     * regression guard against a string-sentinel shortcut.
     */
    @Test
    fun onMessageReceived_payloadWithDashboardNamedDefaultChannel_stillFetchesChannelInfo() {
        Mockito.`when`(daoInterface.getChannel(PEConstants.DEFAULT_CHANNEL_ID)).thenReturn(null)

        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-dashboard-default")
            .setData(mapOf(
                "t" to "Title",
                "b" to "Body",
                "id" to "1003",
                "ci" to PEConstants.DEFAULT_CHANNEL_ID
            ))
            .build()

        service.onMessageReceived(remoteMessage)

        verify(cdnClient, times(1))
            .getChannelInfo(ArgumentMatchers.any(), Mockito.eq(PEConstants.DEFAULT_CHANNEL_ID))
    }

    /**
     * Real channelId already cached in Room → no API call (existing behavior; regression guard).
     */
    @Test
    fun onMessageReceived_payloadWithCachedChannelId_doesNotFetch() {
        val cached = ChannelEntity(
            "real_channel_42", "Promotions", "", "", "",
            "DEFAULT", "DEFAULT", "", "DEFAULT", "", "DEFAULT", "",
            "", false, ""
        )
        Mockito.`when`(daoInterface.getChannel("real_channel_42")).thenReturn(cached)

        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-cached")
            .setData(mapOf(
                "t" to "Title",
                "b" to "Body",
                "id" to "1004",
                "ci" to "real_channel_42"
            ))
            .build()

        service.onMessageReceived(remoteMessage)

        verify(cdnClient, never()).getChannelInfo(ArgumentMatchers.any(), ArgumentMatchers.any())
    }

    // --- Sponsored notification (reFetch=1) path tests ---
    //
    // The 2023 commit that introduced the `payload.channelId = DEFAULT_CHANNEL_ID` mutation existed
    // *only* to keep this branch alive when the backend omits channelId: the original code had a
    // `payload.channelId?.let { … getSponsoredNotificationInfo(...) }` guard that short-circuited
    // on null and silently dropped sponsored pushes. The fix drops that guard and threads a non-null
    // `resolvedChannelId` (sentinel for channelless, real id otherwise). These tests pin that
    // contract.

    private fun stubSponsoredFetchSuccess(title: String = "Sponsored", body: String = "Body") {
        val data: HashMap<String, Any?> = hashMapOf("t" to title, "b" to body)
        val fetchResponse = FetchResponse(0L, data)
        Mockito.`when`(backendClient.fetch(ArgumentMatchers.any()))
            .thenReturn(ImmediateCall(Response.success(fetchResponse)))
    }

    /**
     * Sponsored push (rf=1) without a channelId: backend's sponsored fetch must still fire (this
     * was the original reason for the channelId mutation), but the channel-fetch API must NOT —
     * the resolved channel is the local default-channel sentinel.
     */
    @Test
    fun onMessageReceived_sponsoredChannellessPayload_fetchesSponsoredButNotChannel() {
        stubSponsoredFetchSuccess()

        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-sponsored-no-channel")
            .setData(mapOf(
                "t" to "Title",
                "b" to "Body",
                "id" to "2001",
                "rf" to "1"
                // no "ci" field
            ))
            .build()

        service.onMessageReceived(remoteMessage)

        verify(backendClient, times(1)).fetch(ArgumentMatchers.any())
        verify(cdnClient, never()).getChannelInfo(ArgumentMatchers.any(), ArgumentMatchers.any())
    }

    /**
     * Sponsored push (rf=1) with a real channelId that isn't cached: sponsored fetch fires, then
     * the post-fetch sendNotification flow triggers the channel-fetch API as well (cache miss).
     */
    @Test
    fun onMessageReceived_sponsoredRealChannelPayload_fetchesSponsoredAndChannel() {
        Mockito.`when`(daoInterface.getChannel("real_channel_99")).thenReturn(null)
        stubSponsoredFetchSuccess()

        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-sponsored-real-channel")
            .setData(mapOf(
                "t" to "Title",
                "b" to "Body",
                "id" to "2002",
                "ci" to "real_channel_99",
                "rf" to "1"
            ))
            .build()

        service.onMessageReceived(remoteMessage)

        verify(backendClient, times(1)).fetch(ArgumentMatchers.any())
        verify(cdnClient, times(1))
            .getChannelInfo(ArgumentMatchers.any(), Mockito.eq("real_channel_99"))
    }

    private class ImmediateCall<T>(
        private val response: Response<T>? = null,
        private val failure: Throwable? = null
    ) : Call<T> {
        private var canceled = false

        override fun clone(): Call<T> = ImmediateCall(response, failure)

        override fun execute(): Response<T> =
            response ?: throw IllegalStateException("No response configured")

        override fun enqueue(callback: Callback<T>) {
            if (failure != null) callback.onFailure(this, failure)
            else if (response != null) callback.onResponse(this, response)
            else callback.onFailure(this, IllegalStateException("No response configured"))
        }

        override fun isExecuted(): Boolean = false
        override fun cancel() { canceled = true }
        override fun isCanceled(): Boolean = canceled
        override fun request(): Request = Request.Builder().url("https://example.com").build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
