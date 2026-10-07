package com.pushengage.pushengage.Service

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.ClickRequestEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.response.NetworkResponse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Click-tracking behaviour of [NotificationService] for the intents that
 * PENotificationHandlerActivity forwards after a notification tap.
 *
 * Background: a body tap carries no ACTION_EXTRA because PENotificationBuilder only sets it
 * on action-button intents. ClickRequest.action is a NOT NULL column, so queuing a body tap
 * while offline used to throw SQLiteConstraintException on a bare Thread and kill the
 * :RegisterReceiverService process (customer report against SDK 0.1.0 / 1.0.0). These tests
 * pin the expected behaviour: a missing action is tracked as "" both online and offline, a
 * missing tag is not tracked at all, and a failed insert is logged rather than fatal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationServiceClickTrackingTest {

    private lateinit var context: Context
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val uncaught = AtomicReference<Throwable?>()
    private val uncaughtLatch = CountDownLatch(1)

    /**
     * The real process-wide Room instance for this test. The suite shares one database for
     * the whole JVM (IAMRepository caches the instance it was created with and the IAM tests
     * truncate tables rather than swap instances), so this class must never close or replace
     * it for good: tests clear the ClickRequest table instead, and the one test that injects
     * a failing DAO puts the shared instance back in tearDown.
     */
    private lateinit var sharedDatabase: PERoomDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        PEPrefs(context).hash = "test_hash"
        sharedDatabase = PERoomDatabase.getDatabase(context)
        clearClickRequests()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            uncaught.set(e)
            uncaughtLatch.countDown()
        }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        setRoomSingleton(sharedDatabase)
        clearClickRequests()
    }

    // ---- schema contract that motivates the normalisation ----

    @Test
    fun clickRequestTable_rejectsNullAction() {
        val db = Room.inMemoryDatabaseBuilder(context, PERoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val thrown = try {
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO ClickRequest (deviceHash, tag, action, device_type, device, swv, timezone) " +
                        "VALUES ('h', 't', NULL, 'android', 'mobile', '1.0.0', 'UTC')"
                )
                null
            } catch (e: SQLiteConstraintException) {
                e
            }
            assertTrue(
                "expected NOT NULL failure on ClickRequest.action, got $thrown",
                thrown?.message?.contains("action") == true
            )
        } finally {
            db.close()
        }
    }

    // ---- offline: clicks are queued in Room for NetworkChangeReceiver to replay ----

    @Test
    fun offline_bodyTapWithoutAction_queuesClickWithEmptyAction() {
        setNetworkConnected(false)

        startService(tag = "test_tag", action = null)

        val rows = awaitQueuedRows()
        assertNull("insert thread crashed: ${uncaught.get()}", uncaught.get())
        assertEquals(1, rows.size)
        assertEquals("", rows[0].action)
        assertEquals("test_tag", rows[0].tag)
        assertEquals("test_hash", rows[0].deviceHash)
    }

    @Test
    fun offline_actionButtonTap_queuesClickWithAction() {
        setNetworkConnected(false)

        startService(tag = "test_tag", action = "action1")

        val rows = awaitQueuedRows()
        assertNull("insert thread crashed: ${uncaught.get()}", uncaught.get())
        assertEquals(1, rows.size)
        assertEquals("action1", rows[0].action)
    }

    @Test
    fun offline_missingTag_doesNotQueueOrCrash() {
        setNetworkConnected(false)

        startService(tag = null, action = "action1")

        val crashed = uncaughtLatch.await(500, TimeUnit.MILLISECONDS)
        assertFalse("insert thread crashed: ${uncaught.get()}", crashed)
        assertTrue("nothing should be queued without a tag", dao().getAllClick().isEmpty())
    }

    @Test
    fun offline_missingTag_reportsTrackingFailureToErrorLog() {
        setNetworkConnected(false)

        withErrorLogServer { errorLog ->
            startService(tag = null, action = "action1")

            assertTrackingFailureReported(errorLog)
        }
    }

    @Test
    fun offline_bodyTap_keepsServiceRunningUntilClickIsQueued() {
        setNetworkConnected(false)

        val service = startService(tag = "test_tag", action = null)

        assertFalse("service must stay started while the insert thread runs", shadowOf(service).isStoppedBySelf)
        awaitQueuedRows()
        awaitCondition("service did not stop itself after queuing the click") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun offline_insertFailure_isReportedNotFatal() {
        setNetworkConnected(false)
        val insertAttempted = CountDownLatch(1)
        val failingDao = Mockito.mock(DaoInterface::class.java)
        Mockito.doAnswer {
            insertAttempted.countDown()
            throw SQLiteException("disk I/O error (code 10)")
        }.`when`(failingDao).insertClickRequest(Mockito.any())
        val failingDb = Mockito.mock(PERoomDatabase::class.java)
        Mockito.`when`(failingDb.daoInterface()).thenReturn(failingDao)
        setRoomSingleton(failingDb)

        withErrorLogServer { errorLog ->
            startService(tag = "test_tag", action = "action1")

            assertTrue("insert was never attempted", insertAttempted.await(5, TimeUnit.SECONDS))
            val escaped = uncaughtLatch.await(500, TimeUnit.MILLISECONDS)
            assertFalse("insert failure escaped to the uncaught handler: ${uncaught.get()}", escaped)
            assertTrackingFailureReported(errorLog)
        }
    }

    // ---- online: clicks go straight to the analytics API ----

    @Test
    fun online_bodyTapWithoutAction_sendsEmptyActionParam() {
        setNetworkConnected(true)

        withMockedAnalyticsClient { analyticsClient ->
            startService(tag = "test_tag", action = null)

            Mockito.verify(analyticsClient).notificationClick(
                Mockito.eq("test_hash"), Mockito.eq("test_tag"), Mockito.eq(""),
                Mockito.eq(PEConstants.ANDROID), Mockito.any(), Mockito.any(), Mockito.any()
            )
        }
    }

    @Test
    fun online_actionButtonTap_sendsActionParam() {
        setNetworkConnected(true)

        withMockedAnalyticsClient { analyticsClient ->
            startService(tag = "test_tag", action = "action1")

            Mockito.verify(analyticsClient).notificationClick(
                Mockito.eq("test_hash"), Mockito.eq("test_tag"), Mockito.eq("action1"),
                Mockito.eq(PEConstants.ANDROID), Mockito.any(), Mockito.any(), Mockito.any()
            )
        }
    }

    @Test
    fun online_missingTag_doesNotCallApi() {
        setNetworkConnected(true)

        withMockedAnalyticsClient { analyticsClient ->
            startService(tag = null, action = "action1")

            Mockito.verify(analyticsClient, Mockito.never()).notificationClick(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any()
            )
        }
    }

    // ---- helpers ----

    /** Mirrors the intent PENotificationHandlerActivity builds: tag, id and action as-is, nulls included. */
    private fun startService(tag: String?, action: String?): NotificationService {
        val service = Robolectric.setupService(NotificationService::class.java)
        val intent = Intent()
        intent.putExtra(PEConstants.TAG_EXTRA, tag)
        intent.putExtra(PEConstants.ID_EXTRA, 42)
        intent.putExtra(PEConstants.ACTION_EXTRA, action)
        service.onStartCommand(intent, 0, 1)
        return service
    }

    private fun dao(): DaoInterface = PERoomDatabase.getDatabase(context).daoInterface()

    /** Waits for the service's insert thread to either queue a row or die. */
    private fun awaitQueuedRows(timeoutMs: Long = 20_000): List<ClickRequestEntity> {
        val dao = dao()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (dao.getAllClick().isEmpty() && uncaught.get() == null) {
            if (System.currentTimeMillis() >= deadline) {
                fail("no row queued within ${timeoutMs}ms; uncaught=${uncaught.get()}")
            }
            Thread.sleep(25)
        }
        return dao.getAllClick()
    }

    private fun awaitCondition(message: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) fail(message)
            Thread.sleep(25)
        }
    }

    /** Points the SDK's error-log client at a local server so a report can be observed from any thread. */
    private fun withErrorLogServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val prefs = PEPrefs(context)
            prefs.environment = PEConstants.PROD
            prefs.loggerUrl = server.url("/").toString()
            prefs.siteKey = "site_key"
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun assertTrackingFailureReported(errorLog: MockWebServer) {
        val request = errorLog.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("no error log was sent for the dropped click", request)
        assertEquals("/logs", request!!.path)
        assertTrue(request.body.readUtf8().contains(PEConstants.CLICK_COUNT_TRACKING_FAILED))
    }

    private fun withMockedAnalyticsClient(block: (RestClient.RTApiInterface) -> Unit) {
        val analyticsClient = Mockito.mock(RestClient.RTApiInterface::class.java)
        @Suppress("UNCHECKED_CAST")
        val call = Mockito.mock(Call::class.java) as Call<NetworkResponse>
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val callback = invocation.arguments[0] as Callback<NetworkResponse>
            callback.onResponse(call, Response.success(NetworkResponse()))
            null
        }.`when`(call).enqueue(Mockito.any())
        Mockito.`when`(
            analyticsClient.notificationClick(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any()
            )
        ).thenReturn(call)

        Mockito.mockStatic(RestClient::class.java).use { restClient ->
            restClient.`when`<RestClient.RTApiInterface> {
                RestClient.getAnalyticsClient(Mockito.any(), Mockito.anyMap<String, String>())
            }.thenReturn(analyticsClient)
            block(analyticsClient)
        }
    }

    private fun clearClickRequests() {
        sharedDatabase.openHelper.writableDatabase.execSQL("DELETE FROM ClickRequest")
    }

    private fun setRoomSingleton(db: PERoomDatabase) {
        val field = PERoomDatabase::class.java.getDeclaredField("peRoomDatabaseInstance")
        field.isAccessible = true
        field.set(null, db)
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
}
