package com.pushengage.pushengage.notificationhandling

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.target.CustomTarget
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
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Tests for the double-resume bug in PENotificationImageLoader.loadImageAsync().
 *
 * The production code (line 88-110) uses suspendCoroutine with Glide's CustomTarget.
 * All three callbacks (onResourceReady, onLoadFailed, onLoadCleared) call
 * continuation.resume(), so when Glide fires onLoadCleared after another callback
 * has already resumed, the second resume throws IllegalStateException.
 *
 * These tests mock Glide to capture the real CustomTarget created by the production
 * code, then invoke callbacks in the crash-inducing order. They call the actual
 * private loadImageAsync() method via reflection.
 *
 * CURRENT STATE: Double-callback tests FAIL (IllegalStateException proves the bug).
 * AFTER FIX:    Tests will PASS once suspendCancellableCoroutine + isActive is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationImageLoaderDoubleResumeTest {

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

    /**
     * Sets up the Glide mock chain so that when the production code calls
     * Glide.with(context).asBitmap().load(url).into(customTarget),
     * we capture the CustomTarget and invoke [onInto] with it.
     */
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

    /**
     * Calls the private suspend function PENotificationImageLoader.loadImageAsync(imageUrl)
     * via Kotlin reflection, exercising the actual production code path.
     */
    private suspend fun callProductionLoadImageAsync(imageUrl: String): Bitmap? {
        val method = PENotificationImageLoader::class.declaredMemberFunctions
            .first { it.name == "loadImageAsync" }
        method.isAccessible = true
        return method.callSuspend(loader, imageUrl) as Bitmap?
    }

    // ---- Double-callback tests: FAIL now (prove the bug), PASS after fix ----

    /**
     * Production code: Glide calls onResourceReady, then onLoadCleared.
     * The second continuation.resume() should be silently ignored.
     *
     * FAILS with IllegalStateException against current buggy code.
     * PASSES after fix with suspendCancellableCoroutine + isActive guard.
     */
    @Test
    fun loadImageAsync_onResourceReadyThenOnLoadCleared_doesNotCrash() {
        val mockBitmap = mock(Bitmap::class.java)

        setupGlideMockCapturingTarget { target ->
            target.onResourceReady(mockBitmap, null)
            target.onLoadCleared(null)
        }

        val result = runBlocking {
            callProductionLoadImageAsync("https://example.com/image.png")
        }

        assertEquals(mockBitmap, result)
    }

    /**
     * Production code: Glide calls onLoadFailed, then onLoadCleared.
     * The second continuation.resume() should be silently ignored.
     *
     * FAILS with IllegalStateException against current buggy code.
     * PASSES after fix with suspendCancellableCoroutine + isActive guard.
     */
    @Test
    fun loadImageAsync_onLoadFailedThenOnLoadCleared_doesNotCrash() {
        setupGlideMockCapturingTarget { target ->
            target.onLoadFailed(null)
            target.onLoadCleared(null)
        }

        val result = runBlocking {
            callProductionLoadImageAsync("https://example.com/image.png")
        }

        assertNull(result)
    }

    // ---- Happy path: single callback, should always pass ----

    /**
     * Single onResourceReady — returns the bitmap normally.
     */
    @Test
    fun loadImageAsync_singleOnResourceReady_returnsBitmap() {
        val mockBitmap = mock(Bitmap::class.java)

        setupGlideMockCapturingTarget { target ->
            target.onResourceReady(mockBitmap, null)
        }

        val result = runBlocking {
            callProductionLoadImageAsync("https://example.com/image.png")
        }

        assertEquals(mockBitmap, result)
    }

    /**
     * Single onLoadFailed — returns null normally.
     */
    @Test
    fun loadImageAsync_singleOnLoadFailed_returnsNull() {
        setupGlideMockCapturingTarget { target ->
            target.onLoadFailed(null)
        }

        val result = runBlocking {
            callProductionLoadImageAsync("https://example.com/image.png")
        }

        assertNull(result)
    }

    /**
     * Single onLoadCleared — returns null normally.
     */
    @Test
    fun loadImageAsync_singleOnLoadCleared_returnsNull() {
        setupGlideMockCapturingTarget { target ->
            target.onLoadCleared(null)
        }

        val result = runBlocking {
            callProductionLoadImageAsync("https://example.com/image.png")
        }

        assertNull(result)
    }
}
