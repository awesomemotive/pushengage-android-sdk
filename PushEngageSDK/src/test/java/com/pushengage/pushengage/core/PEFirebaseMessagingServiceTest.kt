package com.pushengage.pushengage.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEPrefs
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEFirebaseMessagingServiceTest {

    private lateinit var context: Context
    private lateinit var service: PEFirebaseMessagingService
    private lateinit var daoInterface: DaoInterface
    private lateinit var roomDatabase: PERoomDatabase
    private lateinit var roomDbStatic: MockedStatic<PERoomDatabase>
    private lateinit var restClientStatic: MockedStatic<RestClient>
    private lateinit var backendClient: RestClient.RTApiInterface

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        service = Robolectric.setupService(PEFirebaseMessagingService::class.java)
        daoInterface = mock(DaoInterface::class.java)
        roomDatabase = mock(PERoomDatabase::class.java)
        backendClient = mock(RestClient.RTApiInterface::class.java)

        roomDbStatic = Mockito.mockStatic(PERoomDatabase::class.java)
        roomDbStatic.`when`<PERoomDatabase> { PERoomDatabase.getDatabase(context) }.thenReturn(roomDatabase)
        Mockito.`when`(roomDatabase.daoInterface()).thenReturn(daoInterface)

        restClientStatic = Mockito.mockStatic(RestClient::class.java)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendClient(context) }
            .thenReturn(backendClient)
    }

    @After
    fun tearDown() {
        restClientStatic.close()
        roomDbStatic.close()
    }

    @Test
    fun onMessageReceived_withMalformedAdditionalData_doesNotCrash() {
        val remoteMessage = RemoteMessage.Builder("sender-id")
            .setMessageId("m-1")
            .setData(
                mapOf(
                    "t" to "Test Title",
                    "b" to "Test Body",
                    "ci" to "test_channel",
                    "ad" to "{malformed-json"
                )
            )
            .build()

        service.onMessageReceived(remoteMessage)

        val payload = PEPrefs(context).payload
        assertTrue(payload.isNotEmpty())
    }

    @Test
    fun onNewToken_whenUpgradeSubscriberFails_doesNotCrash() {
        Mockito.`when`(backendClient.upgradeSubscriber(Mockito.any()))
            .thenReturn(ImmediateCall(failure = IOException("network failure")))

        service.onNewToken("new_token_123")

        verify(backendClient, times(1)).upgradeSubscriber(Mockito.any())
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
