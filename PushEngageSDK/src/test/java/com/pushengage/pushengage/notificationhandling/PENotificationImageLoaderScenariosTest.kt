package com.pushengage.pushengage.notificationhandling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.target.CustomTarget
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Image loading edge-case tests beyond the double-resume test.
 *
 * Tests cover null/empty URLs, single-image paths, exception handling,
 * and completion callback guarantees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationImageLoaderScenariosTest {

    private lateinit var context: Context
    private lateinit var loader: PENotificationImageLoader
    private lateinit var glideStaticMock: MockedStatic<Glide>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        loader = PENotificationImageLoader(context)
        glideStaticMock = Mockito.mockStatic(Glide::class.java)
    }

    @After
    fun tearDown() {
        glideStaticMock.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupGlideMockCapturingTarget(onInto: (CustomTarget<Bitmap?>) -> Unit) {
        val mockRequestManager = mock(RequestManager::class.java)
        val mockRequestBuilder = mock(RequestBuilder::class.java) as RequestBuilder<Bitmap>

        glideStaticMock.`when`<RequestManager> {
            Glide.with(any<Context>())
        }.thenReturn(mockRequestManager)

        whenever(mockRequestManager.asBitmap()).thenReturn(mockRequestBuilder)
        whenever(mockRequestBuilder.load(any<String>())).thenReturn(mockRequestBuilder)

        whenever(mockRequestBuilder.into(any<CustomTarget<Bitmap?>>())).thenAnswer { invocation ->
            val target = invocation.getArgument<CustomTarget<Bitmap?>>(0)
            onInto(target)
            target
        }
    }

    private suspend fun callLoadImageAsync(imageUrl: String): Bitmap? {
        val method = PENotificationImageLoader::class.declaredMemberFunctions
            .first { it.name == "loadImageAsync" }
        method.isAccessible = true
        return method.callSuspend(loader, imageUrl) as Bitmap?
    }

    // --- Null/empty URL tests ---

    @Test
    fun setNotificationImages_allNullUrls_callsCompletion() {
        val payload = FCMPayloadModel(
            commonNotificationImage = null,
            largeIcon = null,
            bigPicture = null,
            notificationId = 1
        )
        val notifBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)

        val latch = CountDownLatch(1)
        var completionCalled = false

        loader.setNotificationImages(payload, notifBuilder) {
            completionCalled = true
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertTrue("Completion should be called when all URLs are null", completionCalled)
    }

    @Test
    fun setNotificationImages_allEmptyUrls_callsCompletion() {
        val payload = FCMPayloadModel(
            commonNotificationImage = "",
            largeIcon = "",
            bigPicture = "",
            notificationId = 1
        )
        val notifBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)

        val latch = CountDownLatch(1)
        var completionCalled = false

        loader.setNotificationImages(payload, notifBuilder) {
            completionCalled = true
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertTrue("Completion should be called when all URLs are empty", completionCalled)
    }

    // --- Single image path tests ---

    @Test
    fun setNotificationImages_onlyCommonImage_callsCompletion() {
        val mockBitmap = mock(Bitmap::class.java)
        setupGlideMockCapturingTarget { target ->
            target.onResourceReady(mockBitmap, null)
        }

        val payload = FCMPayloadModel(
            commonNotificationImage = "https://example.com/image.png",
            largeIcon = null,
            bigPicture = null,
            notificationId = 1
        )
        val notifBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)

        val latch = CountDownLatch(1)
        loader.setNotificationImages(payload, notifBuilder) {
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun setNotificationImages_onlyLargeIcon_callsCompletion() {
        val mockBitmap = mock(Bitmap::class.java)
        setupGlideMockCapturingTarget { target ->
            target.onResourceReady(mockBitmap, null)
        }

        val payload = FCMPayloadModel(
            commonNotificationImage = null,
            largeIcon = "https://example.com/large.png",
            bigPicture = null,
            notificationId = 1
        )
        val notifBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)

        val latch = CountDownLatch(1)
        loader.setNotificationImages(payload, notifBuilder) {
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun setNotificationImages_onlyBigPicture_callsCompletion() {
        val mockBitmap = mock(Bitmap::class.java)
        setupGlideMockCapturingTarget { target ->
            target.onResourceReady(mockBitmap, null)
        }

        val payload = FCMPayloadModel(
            commonNotificationImage = null,
            largeIcon = null,
            bigPicture = "https://example.com/big.png",
            notificationId = 1
        )
        val notifBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)

        val latch = CountDownLatch(1)
        loader.setNotificationImages(payload, notifBuilder) {
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
    }

    // --- Glide exception handling ---

    @Test
    fun loadImageAsync_exceptionInGlide_returnsNull() {
        // Glide.with() throws an exception
        glideStaticMock.`when`<RequestManager> {
            Glide.with(any<Context>())
        }.thenThrow(RuntimeException("Glide destroyed"))

        val result = runBlocking {
            callLoadImageAsync("https://example.com/image.png")
        }

        assertNull("Should return null when Glide throws", result)
    }

    @Test
    fun loadImageAsync_emptyUrl_handledGracefully() {
        setupGlideMockCapturingTarget { target ->
            target.onLoadFailed(null)
        }

        val result = runBlocking {
            callLoadImageAsync("")
        }

        assertNull(result)
    }

    @Test
    fun loadImageAsync_malformedUrl_handledGracefully() {
        setupGlideMockCapturingTarget { target ->
            target.onLoadFailed(null)
        }

        val result = runBlocking {
            callLoadImageAsync("not a valid url")
        }

        assertNull(result)
    }

    // --- Completion called exactly once ---

    @Test
    fun setNotificationImages_completionCalledExactlyOnce() {
        val payload = FCMPayloadModel(
            commonNotificationImage = null,
            largeIcon = null,
            bigPicture = null,
            notificationId = 1
        )
        val notifBuilder = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)

        var completionCount = 0
        val latch = CountDownLatch(1)

        loader.setNotificationImages(payload, notifBuilder) {
            completionCount++
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertEquals("Completion should be called exactly once", 1, completionCount)
    }
}
