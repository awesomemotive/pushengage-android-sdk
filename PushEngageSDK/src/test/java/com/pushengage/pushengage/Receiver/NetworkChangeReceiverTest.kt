package com.pushengage.pushengage.Receiver

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.ClickRequestEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.response.NetworkResponse
import okhttp3.Request
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertNull
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkChangeReceiverTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var receiver: NetworkChangeReceiver
    private lateinit var daoInterface: DaoInterface
    private lateinit var roomDatabase: PERoomDatabase
    private lateinit var analyticsClient: RestClient.RTApiInterface
    private lateinit var pushEngageStatic: MockedStatic<PushEngage>
    private lateinit var roomDbStatic: MockedStatic<PERoomDatabase>
    private lateinit var restClientStatic: MockedStatic<RestClient>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        prefs = PEPrefs(context)
        prefs.hash = ""
        prefs.setIsManuallyUnsubscribed(false)

        receiver = NetworkChangeReceiver()
        daoInterface = mock(DaoInterface::class.java)
        roomDatabase = mock(PERoomDatabase::class.java)
        analyticsClient = mock(RestClient.RTApiInterface::class.java)

        pushEngageStatic = Mockito.mockStatic(PushEngage::class.java)
        roomDbStatic = Mockito.mockStatic(PERoomDatabase::class.java)
        restClientStatic = Mockito.mockStatic(RestClient::class.java)

        roomDbStatic.`when`<PERoomDatabase> { PERoomDatabase.getDatabase(context) }.thenReturn(roomDatabase)
        Mockito.`when`(roomDatabase.daoInterface()).thenReturn(daoInterface)
        Mockito.`when`(daoInterface.getAllClick()).thenReturn(emptyList())
    }

    @After
    fun tearDown() {
        restClientStatic.close()
        roomDbStatic.close()
        pushEngageStatic.close()
    }

    @Test
    fun onReceive_whenOnlineAndEligible_callsSubscribe() {
        setNetworkConnected(true)
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")

        receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

        pushEngageStatic.verify({ PushEngage.subscribe() }, times(1))
    }

    @Test
    fun onReceive_whenOffline_doesNotCallSubscribe() {
        setNetworkConnected(false)
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")

        receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

        pushEngageStatic.verify({ PushEngage.subscribe() }, never())
    }

    @Test
    fun onReceive_whenHashExists_doesNotCallSubscribe() {
        setNetworkConnected(true)
        prefs.hash = "existing_hash"
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")

        receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

        pushEngageStatic.verify({ PushEngage.subscribe() }, never())
    }

    @Test
    fun notificationClick_whenSuccess_deletesQueuedClickFromDao() {
        val click = ClickRequestEntity(
            "device_hash_1",
            "tag_1",
            "action_1",
            PEConstants.ANDROID,
            PEConstants.MOBILE,
            "0.0.6",
            "UTC"
        )

        // notificationCLick depends on receiver.daoInterface internal field.
        setPrivateField(receiver, "daoInterface", daoInterface)

        restClientStatic.`when`<RestClient.RTApiInterface> {
            RestClient.getAnalyticsClient(Mockito.eq(context), Mockito.anyMap<String, String>())
        }.thenReturn(analyticsClient)

        Mockito.`when`(
            analyticsClient.notificationClick(
                click.deviceHash,
                click.tag,
                click.action,
                click.deviceType,
                click.device,
                click.swv,
                click.timezone
            )
        ).thenReturn(ImmediateCall(response = Response.success(NetworkResponse())))

        receiver.notificationCLick(context, click)

        verify(daoInterface, times(1)).deleteClick("device_hash_1", "tag_1")
    }

    @Test
    fun getLifecycle_returnsNull_currentBehaviorDocumented() {
        assertNull(receiver.lifecycle)
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

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field: Field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
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
