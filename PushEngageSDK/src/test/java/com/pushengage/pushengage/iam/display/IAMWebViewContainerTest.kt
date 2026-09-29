package com.pushengage.pushengage.iam.display

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMPosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Robustness of the WebView host view: hostile/degenerate HTML, repeated
 * cleanup, post-cleanup calls, state save/restore (process death), and layout
 * for every message position must never crash the host app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMWebViewContainerTest {

    private lateinit var activity: Activity
    private lateinit var container: IAMWebViewContainer

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        container = IAMWebViewContainer(activity)
    }

    // ----------------------------------------------------------------- content

    @Test
    fun `hostile and degenerate html loads without crashing`() {
        val samples = listOf(
            "",
            "not html at all",
            "<html>", // unterminated
            "<html><body onload=\"throw new Error('x')\"><script>while(true){}</script></body></html>",
            "<html><body>" + "🎉".repeat(10_000) + "</body></html>",
            "<html><head><meta charset=\"utf-16\"></head><body>\u0000\uFFFF</body></html>"
        )
        for (html in samples) {
            container.loadContent(html)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `loadContent after cleanup is a safe no-op`() {
        // A cleaned-up container is end-of-life (the display manager builds a
        // fresh one per display): content loaded into it must not crash and
        // must not resurrect a WebView with the nulled context.
        container.cleanup()
        container.loadContent("<html><body>back</body></html>")
        assertNull(container.webView)
    }

    // ------------------------------------------------------------------ layout

    @Test
    fun `updateLayout succeeds for every position and leaves the container visible`() {
        for (position in IAMPosition.values()) {
            container.updateLayout(position, 120)
            assertEquals(position, container.getCurrentPosition())
            assertEquals(View.VISIBLE, container.visibility)
        }
    }

    @Test
    fun `updateLayout with zero and negative heights does not crash`() {
        container.updateLayout(IAMPosition.CENTER, 0)
        container.updateLayout(IAMPosition.TOP, -5)
    }

    /**
     * Height passed to [IAMWebViewContainer.updateLayout] is in **device pixels**.
     * Run at a density > 1 on purpose: at the default mdpi density dp and px are
     * equal, which hides any unit confusion in the cap arithmetic.
     */
    @Test
    @Config(sdk = [28], qualifiers = "xhdpi")
    fun `center modal height never exceeds 50 percent of the screen in portrait`() {
        val dm = activity.resources.displayMetrics
        assertTrue("test needs density > 1 to be meaningful", dm.density > 1f)
        val cap = (dm.heightPixels * 0.5).toInt()

        container.updateLayout(IAMPosition.CENTER, dm.heightPixels * 3)

        val h = webViewChild().layoutParams.height
        assertTrue("center height $h must not exceed the 50% cap $cap", h in 1..cap)
    }

    @Test
    @Config(sdk = [28], qualifiers = "xhdpi")
    fun `center modal shorter than the cap keeps its own height`() {
        val dm = activity.resources.displayMetrics
        val wanted = (dm.heightPixels * 0.25).toInt()

        container.updateLayout(IAMPosition.CENTER, wanted)

        assertEquals(wanted, webViewChild().layoutParams.height)
    }

    private fun webViewChild(): View {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is android.webkit.WebView) return c
        }
        throw AssertionError("container has no WebView child")
    }

    // ------------------------------------------------------- banner insets

    private val statusBarPx = 63
    private val navBarPx = 48
    private fun dp16() = (16 * activity.resources.displayMetrics.density).toInt()

    /**
     * Hosts [container] in an anchor laid out [anchorTop] px below the window
     * top, shows [position], then dispatches status/nav-bar insets the way the
     * framework does once the view is attached. anchorTop = 0 is an
     * edge-to-edge host (content spans the window, under the bars); anchorTop
     * = statusBarPx is a classic host (content starts below the status bar).
     */
    private fun bannerAfterInsets(position: IAMPosition, anchorTop: Int): FrameLayout.LayoutParams {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's default Activity has a title bar, so android.R.id.content
        // does not start at the window top. Offset the anchor so it sits exactly
        // anchorTop px below the window top, and make it reach the window bottom
        // (edge-to-edge) or stop above the navigation bar (classic).
        val contentTop = IntArray(2).also { content.getLocationInWindow(it) }[1]
        val windowHeight = content.rootView.height
        val anchorBottom = if (anchorTop > 0) windowHeight - navBarPx else windowHeight
        val anchor = FrameLayout(activity)
        content.addView(
            anchor,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, anchorBottom - anchorTop)
                .apply { topMargin = anchorTop - contentTop }
        )
        anchor.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.updateLayout(position, 120)
        shadowOf(Looper.getMainLooper()).idle()
        val anchorY = IntArray(2).also { anchor.getLocationInWindow(it) }[1]
        assertEquals("test setup: anchor must sit at the requested window y", anchorTop, anchorY)
        assertEquals("test setup: anchor bottom", anchorBottom, anchorY + anchor.height)
        ViewCompat.dispatchApplyWindowInsets(
            container,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
                .build()
        )
        shadowOf(Looper.getMainLooper()).idle()
        return webViewChild().layoutParams as FrameLayout.LayoutParams
    }

    // API 30+: separate statusBars/navigationBars insets, as on current devices.
    @Config(sdk = [34])
    @Test
    fun `top banner keeps its 16dp gap below the status bar in an edge-to-edge host`() {
        // Edge-to-edge (Android 15+ at targetSdk 35+, Flutter): the insets reach the
        // container and used to REPLACE the margin, leaving the card flush against
        // the status bar.
        val lp = bannerAfterInsets(IAMPosition.TOP, anchorTop = 0)
        assertEquals(statusBarPx + dp16(), lp.topMargin)
    }

    // API 30+: separate statusBars/navigationBars insets, as on current devices.
    @Config(sdk = [34])
    @Test
    fun `top banner does not add the status bar twice in a classic host`() {
        // Content already starts below the status bar, so only the card margin applies.
        val lp = bannerAfterInsets(IAMPosition.TOP, anchorTop = statusBarPx)
        assertEquals(dp16(), lp.topMargin)
    }

    // API 30+: separate statusBars/navigationBars insets, as on current devices.
    @Config(sdk = [34])
    @Test
    fun `bottom banner keeps its 16dp gap above the navigation bar in an edge-to-edge host`() {
        val lp = bannerAfterInsets(IAMPosition.BOTTOM, anchorTop = 0)
        assertEquals(navBarPx + dp16(), lp.bottomMargin)
    }

    // API 30+: separate statusBars/navigationBars insets, as on current devices.
    @Config(sdk = [34])
    @Test
    fun `bottom banner does not add the navigation bar twice in a classic host`() {
        val lp = bannerAfterInsets(IAMPosition.BOTTOM, anchorTop = statusBarPx)
        assertEquals(dp16(), lp.bottomMargin)
    }

    // ----------------------------------------------------------------- cleanup

    @Test
    fun `cleanup is idempotent and post-cleanup calls are safe`() {
        container.cleanup()
        container.cleanup()

        // Post-cleanup: every public entry point must be null-safe.
        container.show()
        container.hide()
        container.updateLayout(IAMPosition.BOTTOM, 100)
        container.handleAction("anything")
        assertNull(container.getWebView())
    }

    @Test
    fun `detaching from window cleans up the webview`() {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        content.addView(container)
        assertNotNull(container.getWebView())

        content.removeView(container) // triggers onDetachedFromWindow

        assertNull("detach must release the WebView", container.getWebView())
    }

    @Test
    fun `animationTarget falls back to the container itself after cleanup`() {
        assertSame(container.getWebView(), container.animationTarget())
        container.cleanup()
        assertSame(container, container.animationTarget())
    }

    // ------------------------------------------------------------ state cycle

    @Test
    fun `instance state round-trips the position through the framework save-restore path`() {
        container.id = CONTAINER_ID
        container.updateLayout(IAMPosition.BOTTOM, 240)
        val state = android.util.SparseArray<android.os.Parcelable>()
        container.saveHierarchyState(state)

        val restored = IAMWebViewContainer(activity)
        restored.id = CONTAINER_ID
        restored.restoreHierarchyState(state)

        assertEquals(IAMPosition.BOTTOM, restored.getCurrentPosition())
    }

    @Test
    fun `restoring garbage state does not crash`() {
        // Bundle with a corrupt position name — e.g. state written by a
        // different SDK version whose enum constant no longer exists.
        val bundle = Bundle()
        bundle.putParcelable("super_state", View.BaseSavedState.EMPTY_STATE)
        bundle.putString("position", "NOT_A_REAL_POSITION")
        bundle.putInt("content_height", 50)

        val state = android.util.SparseArray<android.os.Parcelable>()
        state.put(CONTAINER_ID, bundle)
        container.id = CONTAINER_ID
        container.restoreHierarchyState(state)

        assertNull("corrupt position must be discarded", container.getCurrentPosition())
    }

    // ----------------------------------------------------------------- actions

    @Test
    fun `action from the page routes to the action listener`() {
        var received: String? = null
        container.setActionListener { actionId ->
            received = actionId
        }

        container.handleAction("btn-7")

        assertEquals("btn-7", received)
    }

    @Test
    fun `dismiss action falls back to the dismiss listener when no action listener is set`() {
        var dismissed = false
        container.setDismissListener {
            dismissed = true
        }

        container.handleAction("dismiss_action")

        assertTrue(dismissed)
    }

    // -------------------------------------------------- shouldDismissOnTap

    /**
     * `shouldDismissOnTap` comes down on the campaign and is the author's
     * instruction: false means "this may only be answered by its own buttons".
     * It was decoded and persisted but never read here, so an outside tap dismissed
     * the message regardless — iOS honours it, so one payload behaved two ways.
     */
    /** A single tap delivered through the gesture path that actually dismisses. */
    private fun tap(x: Float = 10f, y: Float = 10f) {
        val down = android.view.MotionEvent.obtain(
            0L, 0L, android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        val up = android.view.MotionEvent.obtain(
            0L, 10L, android.view.MotionEvent.ACTION_UP, x, y, 0
        )
        container.onTouchEvent(down)
        container.onTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun `a tap does not dismiss a modal that opted out`() {
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.CENTER, 200)
        container.setDismissOnTap(false)

        tap()

        assertTrue("a campaign that opted out must survive the tap", !dismissed)
    }

    @Test
    fun `a tap still dismisses a modal that opted in`() {
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.CENTER, 200)
        container.setDismissOnTap(true)

        tap()

        assertTrue("opting in must keep the tap dismissal", dismissed)
    }

    @Test
    fun `a fling does not dismiss a modal that opted out`() {
        // A fling bypasses the message's own buttons just as a tap does.
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.CENTER, 200)
        container.setDismissOnTap(false)

        val down = android.view.MotionEvent.obtain(
            0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0
        )
        val move = android.view.MotionEvent.obtain(
            0L, 20L, android.view.MotionEvent.ACTION_MOVE, 600f, 10f, 0
        )
        val up = android.view.MotionEvent.obtain(
            0L, 30L, android.view.MotionEvent.ACTION_UP, 900f, 10f, 0
        )
        container.onTouchEvent(down)
        container.onTouchEvent(move)
        container.onTouchEvent(up)
        down.recycle(); move.recycle(); up.recycle()

        assertTrue(!dismissed)
    }

    @Test
    fun `a click reaching the container dismisses a modal that opted in`() {
        // Reaching the container's click handler already means the touch was not
        // consumed by the WebView — it landed outside the message. The old code
        // re-derived that from `it.x`/`it.y`, the container's own layout position
        // rather than the touch point, so its answer was unrelated to where the user
        // actually tapped and the dismissal depended on where the container sat.
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.CENTER, 200)
        container.setDismissOnTap(true)

        container.performClick()

        assertTrue(dismissed)
    }

    @Test
    fun `a click reaching the container respects the opt-out`() {
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.CENTER, 200)
        container.setDismissOnTap(false)

        container.performClick()

        assertTrue(!dismissed)
    }

    @Test
    fun `a click on a banner dismisses it when the campaign opted in`() {
        // Was the opposite assertion: banners ignored the flag, so the same campaign
        // was dismissible by an outside tap on iOS and stuck on Android.
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.TOP, 120)
        container.setDismissOnTap(true)

        container.performClick()

        assertTrue("a banner must honour shouldDismissOnTap like every other position", dismissed)
    }

    @Test
    fun `a click on a banner respects the opt-out`() {
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.updateLayout(IAMPosition.TOP, 120)
        container.setDismissOnTap(false)

        container.performClick()

        assertTrue("the opt-out must hold for banners too", !dismissed)
    }

    // ------------------------------- shouldDismissOnTap at every position

    /**
     * The campaign author ticks "dismiss on tap" once; iOS honours it at all four
     * positions (one `UITapGestureRecognizer` on the presented view, gated only on
     * `shouldDismissOnTap`), Android used to honour it only for `center`.
     *
     * Reproduced on a Pixel 7 / API 34 emulator with the SDK's own `bottom-sheet-1`
     * mock campaign (BOTTOM, shouldDismissOnTap=true): the message occupied
     * y=1820..2295 and a tap at y=1400 did nothing, while its in-message button
     * dismissed it immediately — so taps reached the overlay, position gating
     * discarded them.
     */
    private fun freshContainer(position: IAMPosition, dismissOnTap: Boolean): IAMWebViewContainer =
        IAMWebViewContainer(activity).apply {
            updateLayout(position, 200)
            setDismissOnTap(dismissOnTap)
        }

    /** A tap delivered to [target], returning whether the container consumed it. */
    private fun tapOn(target: IAMWebViewContainer, x: Float, y: Float): Boolean {
        val down = android.view.MotionEvent.obtain(
            0L, 0L, android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        val up = android.view.MotionEvent.obtain(
            0L, 10L, android.view.MotionEvent.ACTION_UP, x, y, 0
        )
        val consumedDown = target.onTouchEvent(down)
        val consumedUp = target.onTouchEvent(up)
        down.recycle()
        up.recycle()
        return consumedDown && consumedUp
    }

    @Test
    fun `an outside tap dismisses at every position when the campaign opted in`() {
        for (position in IAMPosition.values()) {
            var dismissed = false
            val target = freshContainer(position, dismissOnTap = true)
            target.setDismissListener { dismissed = true }

            tapOn(target, 500f, 1400f)

            assertTrue("$position must dismiss on an outside tap", dismissed)
        }
    }

    @Test
    fun `an outside tap dismisses at no position when the campaign opted out`() {
        for (position in IAMPosition.values()) {
            var dismissed = false
            val target = freshContainer(position, dismissOnTap = false)
            target.setDismissListener { dismissed = true }

            tapOn(target, 500f, 1400f)

            assertTrue("$position must survive the tap when opted out", !dismissed)
        }
    }

    @Test
    fun `an outside tap is consumed at every position, whatever the flag says`() {
        // The overlay is a scrim over the whole screen on both platforms: a touch
        // aimed at the message must never reach the host app behind it. "Stop
        // swallowing" is not the fix for the gating bug.
        for (position in IAMPosition.values()) {
            for (dismissOnTap in listOf(true, false)) {
                val target = freshContainer(position, dismissOnTap)
                target.setDismissListener { }

                assertTrue(
                    "$position (dismissOnTap=$dismissOnTap) must swallow the outside tap",
                    tapOn(target, 500f, 1400f)
                )
            }
        }
    }

    @Test
    fun `one outside tap dismisses exactly once`() {
        // The gesture path and the container's click listener both used to invoke the
        // dismiss listener; they now funnel through one method, so a single tap can
        // never be reported twice.
        for (position in IAMPosition.values()) {
            var dismissals = 0
            val target = freshContainer(position, dismissOnTap = true)
            target.setDismissListener { dismissals++ }

            tapOn(target, 500f, 1400f)

            assertEquals("$position dismissed the wrong number of times", 1, dismissals)
        }
    }

    @Test
    fun `a tap on the message content goes to the WebView and never dismisses`() {
        // The inside-the-WebView dispatch is unchanged: content taps belong to the
        // page (buttons, links, scrolling), and only the region outside it is a
        // dismissal target.
        for (position in IAMPosition.values()) {
            var dismissed = false
            val target = freshContainer(position, dismissOnTap = true)
            target.setDismissListener { dismissed = true }
            target.loadContent("<html><body><button>ok</button></body></html>")
            shadowOf(Looper.getMainLooper()).idle()
            val webView = target.getWebView()!!

            // The WebView is unmeasured in a unit test, so it occupies exactly its
            // origin — which makes that origin the only point inside the message.
            val location = IntArray(2)
            webView.getLocationOnScreen(location)
            tapOn(target, location[0].toFloat(), location[1].toFloat())

            assertTrue("$position must not dismiss on a content tap", !dismissed)
        }
    }

    @Test
    fun `the opt-out survives a position update`() {
        // updateLayout re-installs the click handling on every display pass, so the
        // flag has to outlive it rather than being reset to the permissive default.
        var dismissed = false
        container.setDismissListener { dismissed = true }
        container.setDismissOnTap(false)

        container.updateLayout(IAMPosition.CENTER, 200)
        container.updateLayout(IAMPosition.CENTER, 400)
        tap()

        assertTrue(!dismissed)
    }

    @Test
    fun `action with no listeners at all is a safe no-op`() {
        container.handleAction("btn-1")
    }

    // -------------------------------------------------------------- navigation
    // The "Android" JS bridge stays attached for the WebView's lifetime, so the
    // message must never navigate in place — a linked/redirected third-party
    // page would gain access to the bridge (and the app's chrome). Link-outs
    // are expected to go through open_url actions; a raw http(s) link opens in
    // the external browser instead.

    @Test
    fun `in-place navigation is always overridden`() {
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        assertTrue(
            "navigation must be blocked, never loaded in the message WebView",
            client.shouldOverrideUrlLoading(webView, "https://evil.example.com/phish")
        )
    }

    /** A finger on the message, delivered the way the framework delivers it. */
    private fun touchMessage() {
        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = android.view.MotionEvent.obtain(0L, 10L, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0)
        container.dispatchTouchEvent(down)
        container.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun `http and https links the user tapped are routed to the external browser`() {
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        touchMessage()
        client.shouldOverrideUrlLoading(webView, "https://example.com/promo")

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("http(s) links should open in the external browser", started)
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/promo", started.data.toString())
    }

    @Test
    fun `a navigation the page starts on its own does not open the browser`() {
        // `location.href = …` from a load handler used to be indistinguishable from a
        // tapped link: on a device, Chrome came to the foreground the moment the
        // message displayed, with no touch, and the message was then consumed as
        // "app backgrounded". The in-place block is not enough on its own — the
        // browser hand-off has to be earned by a user gesture.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        assertTrue(
            "still never loaded in place",
            client.shouldOverrideUrlLoading(webView, "https://sec01-redirect.example.com/")
        )
        assertNull(
            "no browser without a user gesture behind the navigation",
            shadowOf(activity).nextStartedActivity
        )
    }

    @Test
    fun `a touch authorises a link-out only briefly`() {
        // A tap that happened seconds ago is not the gesture behind this navigation.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        // The window is 3 s — generous because a lift is spent on first use, so a retry
        // loop cannot exploit it — and a lift older than that is not this navigation's.
        touchMessage()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(3_500))
        client.shouldOverrideUrlLoading(webView, "https://example.com/promo")

        assertNull("a stale touch must not authorise a link-out", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a request the WebView marks as gesture-backed opens the browser even with no container touch`() {
        // API 24+: the platform tells us directly. TalkBack activations, for one,
        // never produce a touch on this view tree but do carry a user gesture.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient
        val request = org.mockito.kotlin.mock<android.webkit.WebResourceRequest>()
        org.mockito.kotlin.whenever(request.url).thenReturn(android.net.Uri.parse("https://example.com/a11y"))
        org.mockito.kotlin.whenever(request.hasGesture()).thenReturn(true)

        assertTrue(client.shouldOverrideUrlLoading(webView, request))

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("a gesture-backed request must open the browser", started)
        assertEquals("https://example.com/a11y", started.data.toString())
    }

    @Test
    fun `one tap authorises one hand-off`() {
        // The stamp used to survive a successful hand-off, so a page retrying
        // `location.href` in a loop only had to wait for the user's first touch and
        // then got through on every attempt for the rest of the window.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        touchMessage()
        client.shouldOverrideUrlLoading(webView, "https://example.com/first")
        assertNotNull("the tapped link opens", shadowOf(activity).nextStartedActivity)

        client.shouldOverrideUrlLoading(webView, "https://example.com/second")
        assertNull("the same tap must not pay for a second hand-off", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a scroll without a lift does not authorise a hand-off`() {
        // ACTION_MOVE used to re-stamp on every event, so a two-second scroll held
        // the gate open for the whole gesture. Only a lift — the moment a tapped link
        // actually navigates — counts.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient
        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        container.dispatchTouchEvent(down); down.recycle()
        repeat(3) { i ->
            val move = android.view.MotionEvent.obtain(0L, 10L * (i + 1), android.view.MotionEvent.ACTION_MOVE, 10f, 10f + 20 * i, 0)
            container.dispatchTouchEvent(move); move.recycle()
        }

        client.shouldOverrideUrlLoading(webView, "https://example.com/scrolling")

        assertNull("a finger still down is not a tap", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a request the WebView marks as not gesture-backed still opens after a touch`() {
        // The two signals are OR-ed: below the platform's own flag, a recent lift on
        // the message is what lets a tapped link through.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient
        val request = org.mockito.kotlin.mock<android.webkit.WebResourceRequest>()
        org.mockito.kotlin.whenever(request.url).thenReturn(android.net.Uri.parse("https://example.com/tapped"))
        org.mockito.kotlin.whenever(request.hasGesture()).thenReturn(false)

        touchMessage()
        client.shouldOverrideUrlLoading(webView, request)

        assertNotNull("a touched link must open even when the platform flag is missing", shadowOf(activity).nextStartedActivity)
    }

    /** Calls the page-facing `Android.handleAction` exactly as campaign JS would. */
    private fun bridgeHandleAction(actionId: String) {
        // The bridge hops to the main thread with View.post, which a detached view only
        // queues until it is attached — in production the container is always attached
        // while its page can run, so attach it here too.
        if (container.parent == null) {
            activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(container)
        }
        val webView = container.getWebView() as android.webkit.WebView
        val bridge = shadowOf(webView).getJavascriptInterface("Android")
        assertNotNull("the JS bridge must be registered", bridge)
        bridge!!.javaClass.getMethod("handleAction", String::class.java).invoke(bridge, actionId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a bridge action with no user gesture behind it is refused`() {
        // The documented link-out route is the bridge, not a raw anchor — and it reached
        // the same ACTION_VIEW with no gesture check at all, so a load-handler calling
        // Android.handleAction('<open_url id>') ejected the user exactly as before.
        var received: String? = null
        container.setActionListener { received = it }

        bridgeHandleAction("t02B05")

        assertNull("a script-initiated bridge action must not reach the handler", received)
    }

    @Test
    fun `a bridge action right after a tap is handled`() {
        var received: String? = null
        container.setActionListener { received = it }

        touchMessage()
        bridgeHandleAction("t02B05")

        assertEquals("t02B05", received)
    }

    @Test
    fun `a bridge action is handled without a touch when touch exploration is on`() {
        // TalkBack activates buttons without any MotionEvent reaching this view tree,
        // and the bridge has no platform gesture flag to fall back on. Blocking every
        // campaign button for screen-reader users is the wrong trade, so the gate
        // stands down while touch exploration is enabled.
        val am = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        shadowOf(am).setTouchExplorationEnabled(true)
        var received: String? = null
        container.setActionListener { received = it }

        bridgeHandleAction("t02B06")

        assertEquals("t02B06", received)
    }

    @Test
    fun `a request the WebView marks as not gesture-backed is dropped when nothing was touched`() {
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient
        val request = org.mockito.kotlin.mock<android.webkit.WebResourceRequest>()
        org.mockito.kotlin.whenever(request.url).thenReturn(android.net.Uri.parse("https://example.com/script"))
        org.mockito.kotlin.whenever(request.hasGesture()).thenReturn(false)

        assertTrue(client.shouldOverrideUrlLoading(webView, request))

        assertNull("script-initiated request must not open the browser", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a link-out with no touch opens when touch exploration is on`() {
        // TalkBack activates an anchor without any MotionEvent reaching this view
        // tree, and hasGesture() is advisory and may be false for it — so keying the
        // hand-off on a lift alone dropped every campaign link for screen-reader
        // users. The nav gate now asks the same question the bridge gate does.
        val am = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        shadowOf(am).setTouchExplorationEnabled(true)
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient
        val request = org.mockito.kotlin.mock<android.webkit.WebResourceRequest>()
        org.mockito.kotlin.whenever(request.url)
            .thenReturn(android.net.Uri.parse("https://example.com/a11y-anchor"))
        org.mockito.kotlin.whenever(request.hasGesture()).thenReturn(false)

        client.shouldOverrideUrlLoading(webView, request)

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("a screen-reader activation must open the browser", started)
        assertEquals("https://example.com/a11y-anchor", started.data.toString())
    }

    @Test
    fun `non-http schemes are blocked and never dispatched`() {
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        assertTrue(client.shouldOverrideUrlLoading(webView, "intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(client.shouldOverrideUrlLoading(webView, "javascript:alert(1)"))
        assertTrue(client.shouldOverrideUrlLoading(webView, "file:///etc/passwd"))
        assertTrue(client.shouldOverrideUrlLoading(webView, null as String?))

        assertNull("no intent may be fired for non-http schemes", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `links resolved against the dummy base url are dropped, not opened externally`() {
        // Relative hrefs in campaign HTML resolve against the loadDataWithBaseURL
        // dummy host — sending users to a dead domain would be worse than a no-op.
        val webView = container.getWebView() as android.webkit.WebView
        val client = shadowOf(webView).webViewClient

        assertTrue(client.shouldOverrideUrlLoading(webView, "https://local.content/relative-path"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // ----------------------------------------------------------------- context

    @Test
    fun `updateContext with the same context is a no-op`() {
        val webViewBefore = container.getWebView()
        container.updateContext(activity)
        assertSame(webViewBefore, container.getWebView())
    }

    @Test
    fun `updateContext with a new activity recreates the webview`() {
        val webViewBefore = container.getWebView()
        val newActivity = Robolectric.buildActivity(Activity::class.java).setup().get()

        container.updateContext(newActivity)

        assertNotNull(container.getWebView())
        assertTrue("WebView must be recreated", container.getWebView() !== webViewBefore)
    }

    @Test
    fun `updateContext with a new activity keeps the loaded content`() {
        // The recreated WebView used to be asked to reload(). It had never loaded
        // anything, and the content it was meant to bring back came from
        // loadDataWithBaseURL, which has no URL to reload from — so the message
        // came back as an empty WebView. On a device that is a message that is
        // "displaying" but invisible, and cannot be dismissed.
        container.loadContent("<html><body>keep me</body></html>")
        val newActivity = Robolectric.buildActivity(Activity::class.java).setup().get()

        container.updateContext(newActivity)

        val webView = container.getWebView() as android.webkit.WebView
        val loaded = shadowOf(webView).lastLoadDataWithBaseURL
        assertNotNull("the recreated WebView must have the message content re-loaded", loaded)
        assertTrue(
            "the re-loaded content must be the message that was showing",
            loaded!!.data.contains("keep me")
        )
    }

    // ------------------------------------------------------- height measurement

    @Test
    fun `a measurement the page could not produce is not reported as an error`() {
        // WebKit hands evaluateJavascript the string "null" whenever the measuring
        // script throws or evaluates to NaN. Parsing that as a number threw
        // NumberFormatException — caught, but logged at error on every single
        // message display (issue #52). Nothing is wrong with the message: the
        // authored page reports its own height through PEBridge.reportHeight.
        PELogger.enableLogging(true)

        for (result in listOf("null", "undefined", "", "\"\"", "NaN", "not a number")) {
            val target = IAMWebViewContainer(activity)
            ShadowLog.clear()

            deliverMeasuredHeight(target, result)

            assertTrue(
                "a measurement of $result must not be logged as an error, got: " +
                    errorLogs().joinToString { it.msg },
                errorLogs().isEmpty()
            )
        }
    }

    @Test
    fun `a measurement the page could not produce leaves the recorded height alone`() {
        // Same page, two answers: the page reports a real height, then a later
        // measurement comes back unusable. The good measurement must survive it.
        val measure = armHeightMeasurement(container)

        measure.onReceiveValue("\"180\"")
        shadowOf(Looper.getMainLooper()).idle()
        val measured = recordedContentHeight(container)
        assertTrue("the usable measurement must be recorded first", measured > 0)

        measure.onReceiveValue("null")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "an unusable measurement must not disturb the height already measured",
            measured,
            recordedContentHeight(container)
        )
    }

    @Test
    fun `a fractional css pixel measurement is recorded in device pixels`() {
        deliverMeasuredHeight(container, "\"323.16\"")
        shadowOf(Looper.getMainLooper()).idle()

        val expected = Math.round(323.16f * activity.resources.displayMetrics.density)
        assertEquals(expected, recordedContentHeight(container))
    }

    /**
     * Arms the height measurement the way a real page load does — onPageFinished
     * asks the page for its height — and returns the callback WebKit would answer
     * on, so a test can deliver any value the browser could hand back.
     */
    private fun armHeightMeasurement(target: IAMWebViewContainer): ValueCallback<String> {
        target.loadContent("<html><body>measure me</body></html>")
        val webView = webViewOf(target)
        shadowOf(webView).webViewClient.onPageFinished(webView, "about:blank")

        val callback = shadowOf(webView).lastEvaluatedJavascriptCallback
        assertNotNull("onPageFinished must ask the page for its height", callback)
        return callback
    }

    private fun deliverMeasuredHeight(target: IAMWebViewContainer, result: String) {
        armHeightMeasurement(target).onReceiveValue(result)
    }

    private fun webViewOf(target: IAMWebViewContainer) =
        target.getWebView() as android.webkit.WebView

    /**
     * The height the container is actually working with, read back through the
     * framework save path — the WebView's own layout params are not it, because
     * updateLayout replaces them with the position's params straight afterwards.
     */
    private fun recordedContentHeight(target: IAMWebViewContainer): Int {
        target.id = CONTAINER_ID
        val state = android.util.SparseArray<android.os.Parcelable>()
        target.saveHierarchyState(state)
        return (state.get(CONTAINER_ID) as Bundle).getInt("content_height")
    }

    private fun errorLogs(): List<ShadowLog.LogItem> =
        ShadowLog.getLogs().filter { it.type == Log.ERROR && it.tag == "PushEngage" }

    // ---------------------------------------------------- remote debugging gate

    @After
    fun resetLogging() {
        PELogger.enableLogging(false)
    }

    @Test
    fun `webview debugging stays off in a release build even with SDK logging on`() {
        // Customers ship with SDK logging on to diagnose field issues. WebView
        // debugging is process-global, so switching it on there exposed every
        // WebView in their production app to Chrome DevTools.
        PELogger.enableLogging(true)

        assertFalse(IAMWebViewContainer.shouldEnableWebContentsDebugging(app(debuggable = false)))
    }

    @Test
    fun `webview debugging turns on in a debuggable build with SDK logging on`() {
        PELogger.enableLogging(true)

        assertTrue(IAMWebViewContainer.shouldEnableWebContentsDebugging(app(debuggable = true)))
    }

    @Test
    fun `webview debugging stays off when SDK logging is off`() {
        PELogger.enableLogging(false)

        assertFalse(IAMWebViewContainer.shouldEnableWebContentsDebugging(app(debuggable = true)))
    }

    @Test
    fun `webview debugging stays off without a context`() {
        PELogger.enableLogging(true)

        assertFalse(IAMWebViewContainer.shouldEnableWebContentsDebugging(null))
    }

    private fun app(debuggable: Boolean): Context {
        val app = activity.application
        val info = app.applicationInfo
        info.flags = if (debuggable) {
            info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
        return app
    }

    private val IAMWebViewContainer.webView: View?
        get() = getWebView()

    private companion object {
        const val CONTAINER_ID = 0x7A7A01
    }
}
