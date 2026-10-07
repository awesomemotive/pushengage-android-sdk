package com.pushengage.pushengage.Receiver

import android.content.Context
import android.database.sqlite.SQLiteException
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.junit.Assert.fail
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowNetworkInfo
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The replay runs on a bare Thread in the host app's main process on every connectivity
     * change, including the sticky broadcast delivered at SDK initialisation, so it performs the
     * first Room open of the process at every launch. A database failure there must be reported,
     * never allowed to kill the process.
     */
    @Test
    fun onReceive_whenReplayQueryThrows_isReportedNotFatal() {
        setNetworkConnected(true)
        prefs.hash = "existing_hash"
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")
        Mockito.`when`(daoInterface.getAllClick()).thenThrow(SQLiteException("disk I/O error (code 10)"))

        val uncaught = AtomicReference<Throwable?>()
        val uncaughtLatch = CountDownLatch(1)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            uncaught.set(e)
            uncaughtLatch.countDown()
        }
        val errorLog = MockWebServer()
        errorLog.start()
        try {
            prefs.environment = PEConstants.PROD
            prefs.loggerUrl = errorLog.url("/").toString()
            prefs.siteKey = "site_key"
            errorLog.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

            val escaped = uncaughtLatch.await(500, TimeUnit.MILLISECONDS)
            assertFalse("replay failure escaped to the uncaught handler: ${uncaught.get()}", escaped)
            val request = errorLog.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("no error log was sent for the failed replay", request)
            assertEquals("/logs", request!!.path)
            assertTrue(request.body.readUtf8().contains(PEConstants.CLICK_COUNT_TRACKING_FAILED))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            errorLog.shutdown()
        }
    }

    // ---- replay must send each queued click exactly once ----
    // Root causes seen in production testing on Android 14: deferred connectivity broadcasts
    // arrive in a burst (two onReceive calls milliseconds apart), and the receiver is also
    // registered in the :RegisterReceiverService process because host apps initialise the SDK
    // from Application.onCreate(). Both replayed the same row and the dashboard counted 2 clicks.

    @Test
    fun onReceive_burstOfConnectivityBroadcasts_replaysEachQueuedClickOnce() {
        setNetworkConnected(true)
        prefs.hash = "existing_hash"
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")
        val queue = queuedClicks(click(1L, "tag_1", ""))

        withAnalyticsServer { server ->
            // Slow responses keep the first pass in flight while the second broadcast arrives.
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(1500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(1500, TimeUnit.MILLISECONDS))

            receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))
            receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

            awaitCondition("queued click was never sent and deleted") { queue.isEmpty() }
            Thread.sleep(2500) // give any second pass time to finish before counting
            assertEquals("the queued click must be sent exactly once", 1, server.requestCount)
        }
    }

    @Test
    fun onReceive_inSecondaryProcess_doesNothing() {
        setNetworkConnected(true)
        prefs.hash = ""
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")
        val secondaryProcessReceiver = object : NetworkChangeReceiver() {
            override fun isMainProcess(context: Context): Boolean = false
        }
        val queue = queuedClicks(click(1L, "tag_1", ""))

        withAnalyticsServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            secondaryProcessReceiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))
            Thread.sleep(1000)

            assertEquals("secondary process must not replay clicks", 0, server.requestCount)
            assertEquals("row must stay queued for the main process", 1, queue.size)
            pushEngageStatic.verify({ PushEngage.subscribe() }, never())
        }
    }

    /** android:process on <application> renames the default process away from the package name. */
    @Test
    fun onReceive_inARenamedMainProcess_stillActsAsTheMainProcess() {
        val renamed = context.packageName + ":main"
        val original = context.applicationInfo.processName
        context.applicationInfo.processName = renamed
        ShadowApplication.setProcessName(renamed)
        try {
            setNetworkConnected(true)
            pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")

            receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

            pushEngageStatic.verify({ PushEngage.subscribe() }, times(1))
        } finally {
            context.applicationInfo.processName = original
            ShadowApplication.setProcessName(context.packageName)
        }
    }

    @Test
    fun replay_twoRowsSharingATag_sendsBothAndDeletesEachById() {
        setNetworkConnected(true)
        prefs.hash = "existing_hash"
        pushEngageStatic.`when`<String> { PushEngage.getNotificationPermissionStatus() }.thenReturn("granted")
        val queue = queuedClicks(click(1L, "tag_1", ""), click(2L, "tag_1", "action1"))

        withAnalyticsServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            receiver.onReceive(context, Intent("android.net.conn.CONNECTIVITY_CHANGE"))

            awaitCondition("both queued clicks must be sent and deleted") { queue.isEmpty() }
            assertEquals(2, server.requestCount)
            val actions = (1..2).map { server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("action") }.toSet()
            assertEquals(setOf("", "action1"), actions)
            verify(daoInterface).deleteClickById(1L)
            verify(daoInterface).deleteClickById(2L)
            verify(daoInterface, never()).deleteClick(Mockito.anyString(), Mockito.anyString())
        }
    }

    @Test
    fun getLifecycle_returnsNull_currentBehaviorDocumented() {
        assertNull(receiver.lifecycle)
    }

    private fun click(id: Long, tag: String, action: String) =
        ClickRequestEntity("device_hash_1", tag, action, PEConstants.ANDROID, PEConstants.MOBILE, "1.0.1", "UTC").apply { this.id = id }

    /** A DAO that behaves like the real table: rows stay until a delete removes them. */
    private fun queuedClicks(vararg rows: ClickRequestEntity): MutableList<ClickRequestEntity> {
        val queue = java.util.Collections.synchronizedList(rows.toMutableList())
        Mockito.`when`(daoInterface.getAllClick()).thenAnswer { queue.toList() }
        Mockito.doAnswer { invocation ->
            val id = invocation.getArgument<Long>(0)
            queue.removeAll { it.id == id }
            null
        }.`when`(daoInterface).deleteClickById(Mockito.anyLong())
        Mockito.doAnswer { invocation ->
            val hash = invocation.getArgument<String>(0)
            val tag = invocation.getArgument<String>(1)
            queue.removeAll { it.deviceHash == hash && it.tag == tag }
            null
        }.`when`(daoInterface).deleteClick(Mockito.anyString(), Mockito.anyString())
        return queue
    }

    /** Points the real analytics client at a local server; the replay runs off the test thread, where static mocks do not apply. */
    private fun withAnalyticsServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            prefs.environment = PEConstants.PROD
            prefs.analyticsUrl = server.url("/").toString()
            prefs.siteKey = "site_key"
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun awaitCondition(message: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) fail(message)
            Thread.sleep(25)
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
