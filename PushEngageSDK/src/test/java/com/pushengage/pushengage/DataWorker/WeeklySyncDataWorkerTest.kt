package com.pushengage.pushengage.DataWorker

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.helper.PEUtilities
import com.pushengage.pushengage.model.response.AndroidSyncResponse
import okhttp3.Request
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeeklySyncDataWorkerTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var worker: WeeklySyncDataWorker
    private lateinit var backendCdnClient: RestClient.RTApiInterface
    private lateinit var restClientStatic: MockedStatic<RestClient>
    private lateinit var peUtilitiesStatic: MockedStatic<PEUtilities>
    private lateinit var notificationManagerCompatStatic: MockedStatic<NotificationManagerCompat>
    private lateinit var pushEngageStatic: MockedStatic<PushEngage>
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)
        prefs.siteKey = "weekly_site_key"
        prefs.setIsManuallyUnsubscribed(true) // Keep true to avoid hash-update API path in tests.
        prefs.setIsSubscriberDeleted(false)

        worker = WeeklySyncDataWorker(context, mock(WorkerParameters::class.java))

        backendCdnClient = mock(RestClient.RTApiInterface::class.java)
        restClientStatic = Mockito.mockStatic(RestClient::class.java)
        restClientStatic.`when`<RestClient.RTApiInterface> { RestClient.getBackendCdnClient(context) }
            .thenReturn(backendCdnClient)

        peUtilitiesStatic = Mockito.mockStatic(PEUtilities::class.java)

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
        peUtilitiesStatic.close()
        restClientStatic.close()
    }

    @Test
    fun callAndroidSync_whenNetworkUnavailable_skipsApiCall() {
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(false)

        worker.callAndroidSync()

        verify(backendCdnClient, never()).androidSync(Mockito.anyString())
    }

    @Test
    fun callAndroidSync_whenSiteKeyEmpty_skipsApiCall() {
        // Issue #38: a misconfigured install would fire /sites//sync/android forever via
        // this worker because it's scheduled with KEEP and re-fires regardless of init.
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(true)
        prefs.siteKey = ""

        worker.callAndroidSync()

        verify(backendCdnClient, never()).androidSync(Mockito.anyString())
    }

    @Test
    fun callAndroidSync_whenSiteKeyBlank_skipsApiCall() {
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(true)
        prefs.siteKey = "   "

        worker.callAndroidSync()

        verify(backendCdnClient, never()).androidSync(Mockito.anyString())
    }

    @Test
    fun callAndroidSync_whenActiveSite_updatesPreferences() {
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(true)
        Mockito.`when`(notificationManagerCompat.areNotificationsEnabled()).thenReturn(false)

        val response = activeSyncResponse()
        Mockito.`when`(backendCdnClient.androidSync("weekly_site_key"))
            .thenReturn(ImmediateCall(response = Response.success(response)))

        worker.callAndroidSync()

        assertEquals("https://backend.example.com/", prefs.backendUrl)
        assertEquals("https://cdn.example.com/", prefs.backendCdnUrl)
        assertEquals("https://analytics.example.com/", prefs.analyticsUrl)
        assertEquals("https://trigger.example.com/", prefs.triggerUrl)
        assertEquals("https://optin.example.com/", prefs.optinUrl)
        assertEquals("https://log.example.com/", prefs.loggerUrl)
        assertEquals(2002L, prefs.siteId)
        assertEquals("sender-123", prefs.projectId)
        assertEquals(1L, prefs.isNotificationDisabled())
        assertEquals(PEConstants.ACTIVE, prefs.siteStatus)
    }

    @Test
    fun callAndroidSync_whenActiveSite_runsFcmConfigValidator() {
        // The weekly sync catches dashboard drift between subscribes — sender_id or
        // project_id rotated incorrectly on the dashboard would otherwise go unnoticed
        // until the next subscribe() call. Listener fires within a week of the drift.
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(true)
        Mockito.`when`(notificationManagerCompat.areNotificationsEnabled()).thenReturn(false)

        val response = activeSyncResponse()
        Mockito.`when`(backendCdnClient.androidSync("weekly_site_key"))
            .thenReturn(ImmediateCall(response = Response.success(response)))

        worker.callAndroidSync()

        pushEngageStatic.verify {
            PushEngage.runConfigValidation("sender-123", "project-xyz")
        }
    }

    @Test
    fun callAndroidSync_whenActiveSite_persistsFirebaseProjectId() {
        // setFirebaseProjectId must run alongside setProjectId so the cached prefs
        // remain accurate for the next subscribe()'s init-time advisory check.
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(true)
        Mockito.`when`(notificationManagerCompat.areNotificationsEnabled()).thenReturn(false)

        val response = activeSyncResponse()
        Mockito.`when`(backendCdnClient.androidSync("weekly_site_key"))
            .thenReturn(ImmediateCall(response = Response.success(response)))

        worker.callAndroidSync()

        assertEquals("project-xyz", prefs.firebaseProjectId)
    }

    @Test
    fun callAndroidSync_whenSiteInactive_marksSubscriberDeleted() {
        peUtilitiesStatic.`when`<Boolean> { PEUtilities.checkNetworkConnection(context) }.thenReturn(true)
        val inactiveResponse = inactiveSyncResponse()
        Mockito.`when`(backendCdnClient.androidSync("weekly_site_key"))
            .thenReturn(ImmediateCall(response = Response.success(inactiveResponse)))

        worker.callAndroidSync()

        assertTrue(prefs.isSubscriberDeleted())
    }

    private fun activeSyncResponse(): AndroidSyncResponse {
        val response = AndroidSyncResponse()
        val api = response.Api(
            "https://backend.example.com/",
            "https://cdn.example.com/",
            "https://analytics.example.com/",
            "https://trigger.example.com/",
            "https://optin.example.com/",
            "https://log.example.com/"
        )
        val data = response.Data(
            2002L,
            PEConstants.ACTIVE,
            "Site Name",
            "site-subdomain",
            0L,
            0L,
            "sender-123",
            false,
            api,
            false
        )
        data.setFirebaseProjectId("project-xyz")
        response.data = data
        response.errorCode = 0L
        return response
    }

    private fun inactiveSyncResponse(): AndroidSyncResponse {
        val response = AndroidSyncResponse()
        val api = response.Api("", "", "", "", "", "")
        val data = response.Data(
            2002L,
            "inactive",
            "Site Name",
            "site-subdomain",
            0L,
            0L,
            "sender-123",
            false,
            api,
            false
        )
        response.data = data
        response.errorCode = 0L
        return response
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
