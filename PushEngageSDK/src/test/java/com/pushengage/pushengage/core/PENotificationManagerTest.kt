package com.pushengage.pushengage.core

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest
import com.pushengage.pushengage.notificationchannel.PENotificationChannelHelperType
import com.pushengage.pushengage.notificationhandling.PENotificationBuilderType
import com.pushengage.pushengage.servicehandling.PEServiceHandlerType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var daoInterface: DaoInterface
    private lateinit var notificationManager: NotificationManager
    private lateinit var peNotificationBuilder: PENotificationBuilderType
    private lateinit var peChannelHelper: PENotificationChannelHelperType
    private lateinit var peServiceHandler: FakeServiceHandler
    private lateinit var pushEngageStatic: MockedStatic<PushEngage>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        prefs = PEPrefs(context)
        prefs.siteId = 12345L
        prefs.hash = "test_hash"
        prefs.setDeleteOnNotificationDisable(false)
        prefs.setIsNotificationDisabled(0L)
        prefs.setIsSubscriberDeleted(false)
        prefs.setIsManuallyUnsubscribed(false)

        daoInterface = mock(DaoInterface::class.java)
        notificationManager = mock(NotificationManager::class.java)
        peNotificationBuilder = mock(PENotificationBuilderType::class.java)
        peChannelHelper = mock(PENotificationChannelHelperType::class.java)
        peServiceHandler = FakeServiceHandler()
        pushEngageStatic = Mockito.mockStatic(PushEngage::class.java)
    }

    @After
    fun tearDown() {
        pushEngageStatic.close()
    }

    @Test
    fun determineNotificationSubscriberChanges_enabledAndSubscriberDeleted_callsAddSubscriberApi() {
        prefs.setIsNotificationDisabled(1L)
        prefs.setIsSubscriberDeleted(true)
        prefs.setIsManuallyUnsubscribed(false)

        createManager().determineNotificationSubscriberChanges(true)

        pushEngageStatic.verify({ PushEngage.callAddSubscriberAPI() }, times(1))
        assertEquals(0, peServiceHandler.updateStatusCallCount)
    }

    @Test
    fun determineNotificationSubscriberChanges_disabledAndNotManual_callsUpdateSubscriberStatus() {
        prefs.setIsNotificationDisabled(0L)
        prefs.setIsSubscriberDeleted(false)
        prefs.setIsManuallyUnsubscribed(false)

        createManager().determineNotificationSubscriberChanges(false)

        val request = peServiceHandler.lastUpdateStatusRequest
        assertEquals(1, peServiceHandler.updateStatusCallCount)
        if (request == null) {
            throw AssertionError("Expected updateSubscriberStatus request to be captured")
        }
        assertEquals(12345L, request.siteId)
        assertEquals("test_hash", request.deviceTokenHash)
        assertEquals(1L, request.isUnSubscribed)
    }

    @Test
    fun determineNotificationSubscriberChanges_whenManuallyUnsubscribed_skipsAllServerUpdates() {
        prefs.setIsNotificationDisabled(0L)
        prefs.setIsSubscriberDeleted(false)
        prefs.setIsManuallyUnsubscribed(true)

        createManager().determineNotificationSubscriberChanges(false)

        assertEquals(0, peServiceHandler.updateStatusCallCount)
    }

    @Test
    fun determineNotificationSubscriberChanges_whenStateAlreadyMatches_doesNothing() {
        prefs.setIsNotificationDisabled(0L)
        prefs.setIsSubscriberDeleted(false)
        prefs.setIsManuallyUnsubscribed(false)

        createManager().determineNotificationSubscriberChanges(true)

        assertEquals(0, peServiceHandler.updateStatusCallCount)
    }

    private fun createManager(): PENotificationManager {
        return PENotificationManager(
            context = context,
            payload = FCMPayloadModel(
                title = "Test",
                body = "Body",
                channelId = "test_channel",
                notificationId = 99
            ),
            additionalData = null,
            gson = Gson(),
            prefs = prefs,
            daoInterface = daoInterface,
            notificationManager = notificationManager,
            peNotificationBuilder = peNotificationBuilder,
            peChannelHelper = peChannelHelper,
            peServiceHandler = peServiceHandler
        )
    }

    private class FakeServiceHandler : PEServiceHandlerType {
        var updateStatusCallCount = 0
        var lastUpdateStatusRequest: UpdateSubscriberStatusRequest? = null

        override fun updateSubscriberStatus(updateSubscriberStatusRequest: UpdateSubscriberStatusRequest) {
            updateStatusCallCount += 1
            lastUpdateStatusRequest = updateSubscriberStatusRequest
        }

        override fun getChannelInfo(
            channelId: String,
            notificationManager: NotificationManager,
            notificationBuilder: androidx.core.app.NotificationCompat.Builder,
            payload: FCMPayloadModel,
            completion: (isDefault: Boolean) -> Unit
        ) {
            // Not needed for these tests.
        }

        override fun getSponsoredNotificationInfo(
            fetchRequest: com.pushengage.pushengage.model.request.FetchRequest,
            channelId: String,
            id: Int,
            isRetry: Boolean,
            sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean) -> Unit
        ) {
            // Not needed for these tests.
        }

        override fun trackNotificationViewed(tag: String?, isRetry: Boolean) {
            // Not needed for these tests.
        }
    }
}
