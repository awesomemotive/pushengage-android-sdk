package com.pushengage.pushengage.iam.display

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The browser hand-off gate below API 24, where [android.webkit.WebResourceRequest.hasGesture]
 * does not exist and the plain `String` client is installed. The main container test class
 * runs at sdk 28 and therefore never exercises this client — which is the one that ships to
 * the minSdk the API split exists for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class IAMWebViewContainerLegacyClientTest {

    private lateinit var activity: Activity
    private lateinit var container: IAMWebViewContainer

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        container = IAMWebViewContainer(activity)
    }

    private fun touchMessage() {
        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = android.view.MotionEvent.obtain(0L, 10L, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0)
        container.dispatchTouchEvent(down)
        container.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun `below API 24 the plain client is installed`() {
        val webView = container.getWebView() as android.webkit.WebView
        assertFalse(
            "the gesture-aware client must not be constructed below API 24",
            shadowOf(webView).webViewClient.javaClass.simpleName.contains("GestureAware")
        )
    }

    @Test
    fun `below API 24 a tapped link opens the browser`() {
        val webView = container.getWebView() as android.webkit.WebView
        touchMessage()
        shadowOf(webView).webViewClient.shouldOverrideUrlLoading(webView, "https://example.com/promo")
        assertNotNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `below API 24 a navigation with no touch behind it is dropped`() {
        val webView = container.getWebView() as android.webkit.WebView
        shadowOf(webView).webViewClient.shouldOverrideUrlLoading(webView, "https://example.com/script")
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `below API 24 a link opens with no touch when touch exploration is on`() {
        // This client has no hasGesture() to fall back on, so a TalkBack activation
        // produced neither signal and the link was dropped outright — the API levels
        // where the accessibility drop-out was certain rather than merely likely.
        val am = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        shadowOf(am).setTouchExplorationEnabled(true)
        val webView = container.getWebView() as android.webkit.WebView

        shadowOf(webView).webViewClient.shouldOverrideUrlLoading(webView, "https://example.com/a11y")

        assertNotNull(
            "a screen-reader activation must open the browser below API 24",
            shadowOf(activity).nextStartedActivity
        )
    }
}
