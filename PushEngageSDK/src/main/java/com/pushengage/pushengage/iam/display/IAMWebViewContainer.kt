package com.pushengage.pushengage.iam.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.*
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.annotation.RequiresApi
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.util.IAMWebViewUtil

/**
 * Container class for WebView that handles the display of in-app messages
 * Manages WebView lifecycle and provides proper layout parameters based on message position
 */
internal class IAMWebViewContainer @JvmOverloads constructor(
    private var context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context!!, attrs, defStyleAttr), View.OnTouchListener, IAMActionHandlerInterface {

    private var webView: WebView? = null
    private var currentPosition: IAMPosition? = null
    private var contentHeight: Int = 0
    private var gestureHandler: IAMGestureHandler? = null
    private var dismissListener: (() -> Unit)? = null
    private var actionListener: ((String) -> Unit)? = null
    private var lastSavedScrollPosition: Int = 0
    private var lastHtmlContent: String? = null

    // Store insets for later use
    private var statusBarHeight: Int = 0
    private var navigationBarHeight: Int = 0

    companion object {
        /**
         * Whether to switch on WebView remote debugging. The switch is PROCESS-GLOBAL:
         * it exposes every WebView in the host app (not just this one) to Chrome
         * DevTools. SDK logging alone is not enough — customers ship with it on to
         * diagnose field issues — so it also requires the host app to be a
         * debuggable build, and is never enabled in a release build.
         */
        internal fun shouldEnableWebContentsDebugging(context: Context?): Boolean {
            if (!PELogger.isLoggingEnabled()) return false
            val flags = context?.applicationInfo?.flags ?: return false
            return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }

        private const val KEY_SUPER_STATE = "super_state"
        private const val KEY_POSITION = "position"
        private const val KEY_CONTENT_HEIGHT = "content_height"
        private const val KEY_SCROLL_POSITION = "scroll_position"
        private const val KEY_HTML_CONTENT = "html_content"

        // Safety net: fire an armed entry animation even if page load never
        // completes, so the hidden card can't get stuck invisible.
        private const val ENTRY_ANIMATION_FALLBACK_MS = 800L

        // Dummy origin the campaign HTML is loaded against (loadDataWithBaseURL);
        // relative hrefs resolve to this host and must never leave the app.
        private const val DUMMY_BASE_HOST = "local.content"

        /**
         * How long after a lift on the message a navigation or bridge action still
         * counts as the user's. A tapped link navigates within milliseconds; a
         * button handler that awaits a fetch before navigating needs longer. The
         * window is not what stops a retry loop — a lift is spent the first time it
         * is used (see consumeGesture) — so it can afford to be generous.
         */
        private const val USER_GESTURE_WINDOW_MS = 3_000L
    }

    private var entryAnimationCallback: (() -> Unit)? = null
    private var entryAnimationPending = false

    init {
        setupContainer()
    }

    private fun setupContainer() {
        if (context == null) return

        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setupWebView()
        setupGestureHandler()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (context == null) return

        webView = WebView(context!!).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                // Set a minimum height to ensure clickable area
                minimumHeight = dpToPx(50)
            }

            // Set up WebView settings for proper HTML rendering
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                
                // Ensure text auto-sizing works
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                } else {
                    layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                }
                
                // Fix text size issues
                defaultTextEncodingName = "UTF-8"
                defaultFontSize = 16
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                    shouldEnableWebContentsDebugging(context)
                ) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
            }

            // Enable better touch response for buttons
            this.isClickable = true
            this.isFocusable = true
            this.isFocusableInTouchMode = true
            // Explicitly set for better click handling
            this.setOnClickListener { }

            // Set transparent background to show message styling
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            
            // Don't prevent touch events from passing through
            setOnTouchListener(this@IAMWebViewContainer)
            
            // Only use setNestedScrollingEnabled on API level 21+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                isNestedScrollingEnabled = false
            }
            
            // Set up web client
            webViewClient = createWebViewClient()
            
            // Only add JS interface on API 17+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                PELogger.debug("WebView: Adding JavaScript interface")
                addJavascriptInterface(JsBridge(), "Android")
            } else {
                PELogger.error("WebView: Cannot add JavaScript interface - API level too low")
            }
        }

        addView(webView)
    }

    /**
     * The view exit/entry animations should run on: the sized WebView card, not
     * this full-screen (mostly transparent) container. Animating the card keeps
     * the hardware-layer texture small and makes slide distances match the
     * card's height instead of the screen's.
     */
    fun animationTarget(): View = webView ?: this

    /**
     * Arms a one-shot callback fired when the loaded content is ready to be
     * shown (first page-finish after arming). The animation target is hidden
     * until then so the card doesn't pop in before its entry animation runs.
     * Not used on configuration-change re-displays, which must appear
     * immediately without animating.
     */
    fun armEntryAnimation(callback: () -> Unit) {
        entryAnimationCallback = callback
        entryAnimationPending = true
        animationTarget().alpha = 0f
        postDelayed({ fireEntryAnimation() }, ENTRY_ANIMATION_FALLBACK_MS)
    }

    private fun fireEntryAnimation() {
        if (!entryAnimationPending) return
        entryAnimationPending = false
        val callback = entryAnimationCallback
        entryAnimationCallback = null
        // Post so the height applied from content measurement gets a layout
        // pass before the animation reads the view's size.
        animationTarget().post { callback?.invoke() }
    }

    /**
     * All in-place navigation is blocked (see shouldOverrideUrlLoading).
     * Real http(s) links open in the external browser — except ones resolved
     * against the loadDataWithBaseURL dummy host (relative hrefs), which would
     * send users to a dead domain. Everything else (javascript:, file:,
     * intent:, ...) is dropped.
     *
     * The browser hand-off refuses navigations with no user interaction behind
     * them ([userInitiated]). A page script running `location.href = …` from its
     * load handler is blocked in place like everything else, but it used to earn
     * the same ACTION_VIEW as a tapped link: the app was thrown into the browser
     * the moment the message displayed, with no touch, and the message was then
     * consumed as "app backgrounded". What this cannot tell apart is a script
     * navigating from inside a genuine tap's handler — no platform signal
     * distinguishes "the user tapped this link" from "the user tapped something
     * and a script navigated" — so that still passes, once per tap. Campaign HTML
     * is first-party, so this is containment rather than a trust boundary; the
     * bridge route (JsBridge.handleAction) is gated the same way.
     */
    private fun handleBlockedNavigation(url: String?, userInitiated: Boolean) {
        val uri = try {
            android.net.Uri.parse(url ?: return)
        } catch (e: Exception) {
            null
        } ?: return
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host == DUMMY_BASE_HOST) {
            PELogger.debug("WebView: blocked in-place navigation to $url")
            return
        }
        if (!userInitiated) {
            PELogger.debug("WebView: refusing link-out to $url — no user gesture behind it (script-initiated)")
            return
        }
        val ctx = context ?: return
        consumeGesture()
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            if (ctx !is android.app.Activity) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            PELogger.debug("WebView: opened link in external browser: $url")
        } catch (e: Exception) {
            PELogger.error("WebView: unable to open link externally: ${e.message}", e)
        }
    }

    /**
     * The gesture-aware client is a separate class, instantiated only on API 24+,
     * because its override names [WebResourceRequest] (API 21) and calls
     * [WebResourceRequest.hasGesture] (API 24). Keeping both out of the class that
     * loads on every supported API level means minSdk 16 never sees either.
     */
    private fun createWebViewClient(): WebViewClient =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GestureAwareMessageWebViewClient()
        } else {
            MessageWebViewClient()
        }

    private open inner class MessageWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            PELogger.debug("WebView: onPageStarted, URL: $url")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            PELogger.debug("WebView: onPageFinished, URL: $url")

            // Get the content height through multiple methods
            measureContentHeight()

            // Content is ready — run the armed entry animation, if any
            fireEntryAnimation()

            // Apply position-specific JavaScript fixes
            currentPosition?.let { applyPositionSpecificFixes(it) }

            // Add shadow for better visibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view?.elevation = 10f
            }

            // Force layout update
            view?.forceLayout()
            view?.requestLayout()
            view?.invalidate()
        }

        // The "Android" JS bridge stays attached for the WebView's lifetime, so
        // nothing may ever navigate this WebView in place: a linked (or
        // script-redirected) third-party page would gain access to the bridge
        // and the app's chrome. Link-outs are expected to go through open_url
        // actions; a raw http(s) link is handed to the external browser instead —
        // unless nothing the user did stands behind the navigation. This String
        // overload is what every API level calls (the WebResourceRequest overload
        // delegates to it by default), and it has no platform gesture flag to
        // consult, so it asks the same question the bridge does: a recent lift on
        // the message, or a screen reader driving it. Touch exploration has to
        // count here too — TalkBack activates an anchor without any MotionEvent
        // reaching this view tree, so keying on the lift alone silently dropped
        // every campaign link for screen-reader users on API 16-23, where this is
        // the only client installed.
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            handleBlockedNavigation(url, userInitiated = userGestureBehind())
            return true
        }

        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            PELogger.error("WebView: Error loading content: $description (code: $errorCode)")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private inner class GestureAwareMessageWebViewClient : MessageWebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // hasGesture() is the platform's own answer, and it covers activations
            // that never touch this view tree (TalkBack, for one). It is documented
            // as advisory — it may be false for a genuinely user-initiated request,
            // including a TalkBack activation — so the same fallback the bridge uses
            // counts as well: a recent lift, or a screen reader driving the message.
            val userInitiated = request?.hasGesture() == true || userGestureBehind()
            handleBlockedNavigation(request?.url?.toString(), userInitiated)
            return true
        }
    }

    private fun setupGestureHandler() {
        gestureHandler = IAMGestureHandler(context!!, this).apply {
            setOnDismissListener { dismissListener?.invoke() }
        }
    }

    fun updateContext(newContext: Context) {
        if (newContext == context) return
        
        context = newContext

        // Recreate the WebView against the new context. In production the detach
        // that precedes this has already destroyed the old WebView via cleanup();
        // the explicit destroy() covers a container that was never attached.
        val previous = webView
        removeAllViews()
        previous?.destroy()
        setupWebView()
        setupGestureHandler()

        // The new WebView has loaded nothing, so reload() had nothing to reload —
        // and the message came from loadDataWithBaseURL, which has no URL to
        // reload from in the first place. That left the recreated view empty: a
        // message the SDK counted as displaying, invisible on screen, and
        // undismissable. Re-load the HTML kept for exactly this.
        lastHtmlContent?.let { loadContent(it) }
    }

    fun setDismissListener(listener: () -> Unit) {
        dismissListener = listener
    }

    fun setActionListener(listener: (String) -> Unit) {
        actionListener = listener
    }

    /**
     * Clears the DOM storage held against [DUMMY_BASE_HOST].
     *
     * Every campaign is loaded against that one synthetic origin, and DOM storage
     * is origin-scoped, so without this they all share a single `localStorage` and
     * `sessionStorage` namespace — which survives not just the next campaign but
     * app restarts. Two unrelated campaigns can then read and overwrite each
     * other's keys: whatever the first one recorded is visible to the second, and a
     * common key name silently collides.
     *
     * `clearCache` and `clearHistory` in [cleanup] do not cover this. They clear
     * the HTTP cache and the back/forward list; DOM storage lives in the app's
     * WebView data directory and `destroy()` does not remove it either.
     *
     * Scoped to this origin deliberately — `WebStorage.deleteAllData()` is
     * process-global and would delete the host app's own WebView storage, which is
     * none of the SDK's business.
     */
    private fun clearMessageStorage() {
        try {
            WebStorage.getInstance().deleteOrigin("https://$DUMMY_BASE_HOST")
        } catch (e: Exception) {
            // Never let storage hygiene stop a message from displaying.
            PELogger.error("IAM: could not clear message storage: ${e.message}", e)
        }
    }

    fun loadContent(htmlContent: String) {
        if (webView == null) {
            PELogger.debug("loadContent: WebView was null, setting up")
            setupWebView()
        }

        // Store content for potential restoration
        lastHtmlContent = htmlContent
        
        // Process the HTML content to ensure proper height measurement
        val processedHtml = IAMWebViewUtil.prepareHtmlContent(htmlContent)
        
        // Log a preview of the HTML for debugging
        PELogger.debug("loadContent: HTML content preview: " + 
            if (htmlContent.length > 100) htmlContent.substring(0, 100) + "..." else htmlContent)
        
        PELogger.debug("loadContent: Loading HTML content, length: ${processedHtml.length}")
        
        // Reset contentHeight before loading new content
        contentHeight = 0
        
        // Start each campaign with an empty storage namespace: they all share one
        // synthetic origin, so otherwise the previous campaign's localStorage is
        // still there to be read or clobbered.
        clearMessageStorage()

        // Use loadDataWithBaseURL which provides more reliable rendering
        webView?.loadDataWithBaseURL(
            "https://$DUMMY_BASE_HOST",  // Use a dummy base URL to help with relative paths
            processedHtml,  // The HTML content
            "text/html",    // MIME type
            "UTF-8",        // Encoding
            null            // No history URL
        )
        
        // Force redraw
        webView?.invalidate()
    }

    private fun measureContentHeight() {
        // FULL messages fill the whole window and must NOT be resized to their
        // measured content height. Resizing mid-load also locks the page's
        // 100vh to the smaller height, collapsing the takeover into a box at
        // the top. Keep the WebView MATCH_PARENT (set by updateLayout) and skip
        // measurement entirely.
        if (currentPosition == IAMPosition.FULL) {
            webView?.layoutParams = webView?.layoutParams?.apply {
                height = fullScreenHeightPx()
            }
            webView?.visibility = View.VISIBLE
            this.visibility = View.VISIBLE
            return
        }
        webView?.let { view ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                PELogger.debug("measureContentHeight: Using evaluateJavascript (API 19+)")
                
                // More comprehensive JavaScript to get accurate content height
                view.evaluateJavascript(
                    """
                    (function() {
                        function getComputedHeight() {
                            // Get height including any padding, margins, etc.
                            var computedStyle = window.getComputedStyle(document.body);
                            var bodyHeight = document.body.getBoundingClientRect().height;
                            var totalMargin = parseFloat(computedStyle.marginTop) + parseFloat(computedStyle.marginBottom);
                            var totalPadding = parseFloat(computedStyle.paddingTop) + parseFloat(computedStyle.paddingBottom);
                            var totalBorder = parseFloat(computedStyle.borderTopWidth) + parseFloat(computedStyle.borderBottomWidth);
                            return bodyHeight + totalMargin + totalPadding + totalBorder;
                        }
                        
                        // Get height from multiple measuring techniques
                        var body = document.body, html = document.documentElement;
                        var javascriptHeight = Math.max(
                            body.scrollHeight, body.offsetHeight, 
                            html.clientHeight, html.scrollHeight, html.offsetHeight,
                            getComputedHeight()
                        );
                        
                        // Check for any tall elements - scan all elements for their height
                        var allElements = document.getElementsByTagName('*');
                        var tallestElement = 0;
                        for (var i = 0; i < allElements.length; i++) {
                            var elemHeight = allElements[i].offsetHeight;
                            if (elemHeight > tallestElement) {
                                tallestElement = elemHeight;
                            }
                            
                            // Also check if this is the modal element (special handling)
                            if (allElements[i].className && 
                                (allElements[i].className.indexOf('modal') !== -1 || 
                                 allElements[i].className.indexOf('banner') !== -1 || 
                                 allElements[i].className.indexOf('sheet') !== -1)) {
                                // Get full height including any margins/padding
                                var style = window.getComputedStyle(allElements[i]);
                                var elementTotalHeight = allElements[i].offsetHeight + 
                                    parseFloat(style.marginTop) + 
                                    parseFloat(style.marginBottom) +
                                    parseFloat(style.paddingTop) + 
                                    parseFloat(style.paddingBottom);
                                    
                                // Update tallest if this is taller
                                if (elementTotalHeight > tallestElement) {
                                    tallestElement = elementTotalHeight;
                                }
                            }
                        }
                        
                        // Special handling for CENTER modals - scan for specific container elements
                        var modal = document.querySelector('.modal');
                        var modalHeight = modal ? modal.offsetHeight : 0;
                        
                        // Return the max of all approaches
                        return Math.max(javascriptHeight, tallestElement, modalHeight);
                    })();
                    """.trimIndent()
                ) { result ->
                    try {
                        // What arrives is the JSON of the script's value, so it is not
                        // necessarily a number: WebKit hands back the string "null"
                        // whenever the script threw or produced NaN, and parsing that
                        // threw NumberFormatException on every single display. A
                        // measurement we cannot read is not an error and nothing here
                        // can repair it — the authored page reports its own height
                        // through PEBridge.reportHeight, which is what sizes the
                        // message — so skip and leave the message as it is. Parsed as
                        // a float because the height is a CSS pixel value and can be
                        // fractional on non-integer-density displays (e.g. "323.16");
                        // toInt() used to throw on exactly those.
                        val cleanResult = result?.trim('"')?.trim()
                        val cssPx = cleanResult?.toFloatOrNull()
                        if (cssPx == null || !cssPx.isFinite()) {
                            PELogger.debug(
                                "measureContentHeight: no usable height in JS result " +
                                    "\"$result\" — leaving the message as it is"
                            )
                        } else {
                            // JS reports CSS pixels; convert to device pixels so this is
                            // directly comparable with layout heights and the CENTER cap.
                            val height = cssPxToDevicePx(cssPx)
                            PELogger.debug("measureContentHeight: Got height from JS: $height px")

                            // Always take the maximum height we've encountered
                            val finalHeight = Math.max(height, contentHeight)
                            contentHeight = finalHeight

                            // Set the WebView height directly
                            view.post {
                                view.layoutParams = view.layoutParams.apply {
                                    this.height = finalHeight
                                    PELogger.debug("measureContentHeight: Setting WebView height to $finalHeight")
                                }

                                // Then update the layout based on position
                                currentPosition?.let { position ->
                                    PELogger.debug("measureContentHeight: Updating layout with position $position and height $finalHeight")
                                    updateLayout(position, finalHeight)
                                }

                                // Ensure visibility
                                view.visibility = View.VISIBLE

                                // Force layout refresh
                                view.requestLayout()
                                requestLayout()

                                // Content taller than the screen must stay reachable
                                ensureScrollableIfClipped()
                            }
                        }
                    } catch (e: Exception) {
                        PELogger.error("Error measuring height: ${e.message}", e)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                // Fallback for API 17/18: use loadUrl and JS interface
                PELogger.debug("measureContentHeight: Using loadUrl with JS bridge (API 17-18)")
                view.loadUrl("javascript:window.Android.onContentHeightMeasured(Math.max(document.body.scrollHeight, document.body.offsetHeight, document.documentElement.clientHeight, document.documentElement.scrollHeight, document.documentElement.offsetHeight));")
            } else {
                // API < 17: No JS bridge, fallback to WRAP_CONTENT
                PELogger.debug("measureContentHeight: API too low for JS bridge, using WRAP_CONTENT")
                view.layoutParams = view.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        } ?: PELogger.debug("measureContentHeight: WebView is null")
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        // Always return false when the touched view is the WebView to allow click events
        if (v == webView) {
            return false
        }
        
        // For other views, we can use the original logic
        return false
    }
    
    /**
     * When a finger was last on this message, as [SystemClock.uptimeMillis];
     * -1 until the first touch. Read by [hasRecentTouch].
     */
    private var lastTouchUptimeMs: Long = -1L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Every touch aimed at the message — on the WebView or on the scrim around
        // it — enters the view tree here, so this is the one place that knows a
        // finger was just on the message. Only the LIFT is recorded: a tapped link
        // navigates on touchend, whereas a scroll is a stream of ACTION_MOVEs that
        // must not hold the gate open for the whole gesture.
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            lastTouchUptimeMs = SystemClock.uptimeMillis()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hasRecentTouch(): Boolean =
        lastTouchUptimeMs >= 0 &&
            SystemClock.uptimeMillis() - lastTouchUptimeMs <= USER_GESTURE_WINDOW_MS

    /**
     * Whether a user interaction stands behind whatever the page is asking for
     * right now: a recent lift on the message, or a screen reader driving it.
     * TalkBack activates buttons without any MotionEvent reaching this view tree
     * and the bridge has no platform gesture flag to fall back on, so blocking
     * every campaign button for screen-reader users would be the wrong trade.
     */
    private fun userGestureBehind(): Boolean = hasRecentTouch() || touchExplorationEnabled()

    private fun touchExplorationEnabled(): Boolean {
        val am = context?.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager
        return am?.isTouchExplorationEnabled == true
    }

    /**
     * One tap pays for one thing. Spent when a hand-off or a bridge action is
     * accepted, so a page retrying in a loop cannot ride a single lift for the
     * rest of the window.
     */
    private fun consumeGesture() {
        lastTouchUptimeMs = -1L
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // For gestures inside the WebView, let the WebView handle it with higher priority
        if (webView != null && isPointInsideView(event.rawX, event.rawY, webView!!)) {
            PELogger.debug("onTouchEvent: Touch inside WebView, letting WebView handle it")
            return webView!!.dispatchTouchEvent(event)
        }

        // Outside the message, at EVERY position: the gesture handler decides whether
        // this is a dismissal, and `shouldDismissOnTap` alone answers that. Banners
        // used to consume the touch here without ever offering it to the handler, so a
        // campaign that asked to be dismissible was dismissible on iOS and stuck on
        // Android.
        if (gestureHandler != null) {
            gestureHandler?.onTouchEvent(event)
        } else {
            super.onTouchEvent(event)
        }

        // Consumed either way, dismissed or not: the overlay is a scrim over the whole
        // screen (as the presented controller is on iOS), so a touch aimed at the
        // message must never fall through to the host app behind it.
        return true
    }

    fun cleanup() {
        clearMessageStorage()
        webView?.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
        webView = null
        
        gestureHandler?.cleanup()
        gestureHandler = null
        
        dismissListener = null
        actionListener = null
        context = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

    /**
     * Updates the layout based on message position and content height
     */
    fun updateLayout(position: IAMPosition, height: Int) {
        currentPosition = position
        
        // Store the height if provided (for reference, but we'll use WRAP_CONTENT)
        if (height > 0) {
            contentHeight = height
            PELogger.debug("updateLayout: Content height reference is now $contentHeight")
        }

        PELogger.debug("updateLayout: Setting layout for position $position")
        
        // Apply special handling for each position type
        val layoutParams = when (position) {
            IAMPosition.TOP -> createTopLayoutParams()
            IAMPosition.BOTTOM -> createBottomLayoutParams()
            IAMPosition.CENTER -> createCenterLayoutParams()
            IAMPosition.FULL -> createFullScreenLayoutParams()
        }
        
        // Apply the layout parameters to WebView
        webView?.layoutParams = layoutParams
        
        // One click policy for every position — the campaign's `shouldDismissOnTap`
        // is the only input, exactly as on iOS. This is the non-touch entry (a
        // programmatic or accessibility `performClick`); touch taps arrive through
        // `onTouchEvent` -> the gesture handler. Both route into `onOutsideTap`, so
        // there is a single place that invokes the dismiss listener.
        //
        // No hit test here: `onTouchEvent` hands anything inside the WebView to the
        // WebView first, so a click arriving here already landed outside the
        // message. The previous version re-derived that from `it.x`/`it.y` — the
        // container's own layout position, not the touch point — so its verdict had
        // nothing to do with where the user tapped. `isPointInsideView` is the
        // helper for a real hit test, and it needs raw coordinates a click has not
        // got.
        this.setOnClickListener { onOutsideTap() }

        // Set up gesture handling
        setupGestureHandling(position)

        // Reset scroll position to top
        webView?.scrollY = 0
        
        // Apply position-specific JavaScript for layout fixes
        applyPositionSpecificFixes(position)
        
        // Ensure the WebView is visible
        webView?.visibility = View.VISIBLE
        this.visibility = View.VISIBLE
        
        // Force immediate layout processing
        webView?.requestLayout()
        requestLayout()
        invalidate()
        
        PELogger.debug("updateLayout: Layout updated for position $position")
    }

    /**
     * If the page is taller than the WebView — content physically cannot fit the
     * screen (e.g. a tall card in landscape) — allow the page to scroll so the
     * overflow stays reachable. Authored HTML commonly sets
     * `html,body { overflow: hidden }`, assuming the container always matches the
     * content height; when the screen is shorter, that clips content (buttons!)
     * with no way to reach it. Scroll is enabled ONLY in that case — content that
     * fits keeps the author's exact styling.
     */
    private fun ensureScrollableIfClipped(retriesLeft: Int = 5) {
        val wv = webView ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        wv.post {
            // The view may not be laid out yet (height 0) — especially right
            // after a rotation re-attach, where the WebView was just recreated.
            // Retry until layout lands instead of silently skipping (a silent
            // skip left tall content unscrollable after rotation).
            if (wv.height <= 0) {
                if (retriesLeft > 0) {
                    postDelayed({ ensureScrollableIfClipped(retriesLeft - 1) }, 150)
                }
                return@post
            }
            // contentHeight is already device pixels (converted at ingestion).
            val contentPx = contentHeight
            if (contentHeight > 0 && contentPx > wv.height + dpToPx(8)) {
                PELogger.debug(
                    "ensureScrollableIfClipped: content ${contentPx}px > view ${wv.height}px — enabling page scroll"
                )
                wv.isVerticalScrollBarEnabled = true
                wv.evaluateJavascript(
                    // Percentage heights are self-referential inside a
                    // content-sized WebView (100% = the clipped viewport, not the
                    // content), so an authored card div with height:100% gets
                    // pinned shorter than its own text — which then paints past
                    // the card background onto the transparent page (over the
                    // app). Same degenerate case as the FULL-position 100vh
                    // workaround: convert to min-height so the card can grow.
                    "(function(){" +
                        "document.documentElement.style.overflowY='auto';" +
                        "document.body.style.overflowY='auto';" +
                        "var kids=document.body.children;" +
                        "for(var i=0;i<kids.length;i++){" +
                        "  if(kids[i].style && kids[i].style.height==='100%'){" +
                        "    kids[i].style.minHeight='100%';" +
                        "    kids[i].style.height='auto';" +
                        "  }" +
                        "}})();",
                    null
                )
            }
        }
    }

    private fun applyPositionSpecificFixes(position: IAMPosition) {
        // Add JavaScript to fix rendering issues based on position
        webView?.let { wv ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // Authored HTML is rendered faithfully — the SDK does NOT restyle
                // it (no overflow/height/display mutations; that broke valid
                // content, e.g. object-fit images painting over siblings). The
                // only JS injected here is:
                //  - CENTER: a measurement-only fallback height report for content
                //    that doesn't call PEBridge.reportHeight itself
                //  - FULL: the 100vh workaround — the
                //    WebView locks vh to a stale viewport measured before layout,
                //    so a real platform quirk needs pixel-pinning
                // TOP/BOTTOM need nothing: the native card wraps content and the
                // author's PEBridge.reportHeight / the fallback measurer size it.
                val jsCode = when (position) {
                    IAMPosition.CENTER -> """
                        (function() {
                            // Measurement only — no style mutations.
                            setTimeout(function() {
                                var docHeight = Math.max(
                                    document.body.scrollHeight,
                                    document.body.offsetHeight,
                                    document.documentElement.scrollHeight,
                                    document.documentElement.offsetHeight
                                );
                                if (window.Android && typeof window.Android.onContentHeightMeasured === 'function') {
                                    Android.onContentHeightMeasured(docHeight);
                                }
                            }, 100);
                            return true;
                        })();
                    """.trimIndent()
                    IAMPosition.FULL -> """
                        (function() {
                            // The full-screen HTML sizes itself with 100vh.
                            // WebView locks vh (and %) to a stale/zero viewport
                            // measured before the view is laid out at full size,
                            // so the takeover collapses into a box at the top.
                            // Force explicit pixel heights from the *current*
                            // window.innerHeight so the page fills the window,
                            // and make the direct children stretch too.
                            var h = window.innerHeight;
                            if (!h) { return false; }
                            [document.documentElement, document.body].forEach(function(e){
                                e.style.height = h + 'px';
                                e.style.minHeight = h + 'px';
                                e.style.margin = '0';
                            });
                            var kids = document.body.children;
                            for (var i = 0; i < kids.length; i++) {
                                kids[i].style.minHeight = h + 'px';
                            }
                            return true;
                        })();
                    """.trimIndent()
                    else -> ""
                }
                
                if (jsCode.isNotEmpty()) {
                    wv.evaluateJavascript(jsCode, null)
                }
            }
        }
    }

    /**
     * Shows the WebView with animation
     */
    fun show() {
        webView?.visibility = View.VISIBLE
    }

    /**
     * Hides the WebView with animation
     */
    fun hide() {
        webView?.visibility = View.INVISIBLE
    }

    /**
     * Handle configuration changes and save state
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Save current scroll position
        lastSavedScrollPosition = webView?.scrollY ?: 0
        
        // Update layout for new configuration
        currentPosition?.let { position ->
            updateLayout(position, contentHeight)
        }
    }

    /**
     * Save instance state
     */
    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable(KEY_SUPER_STATE, super.onSaveInstanceState())
        bundle.putString(KEY_POSITION, currentPosition?.name)
        bundle.putInt(KEY_CONTENT_HEIGHT, contentHeight)
        bundle.putInt(KEY_SCROLL_POSITION, webView?.scrollY ?: 0)
        bundle.putString(KEY_HTML_CONTENT, lastHtmlContent)
        return bundle
    }

    /**
     * Restore instance state
     */
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            // Restore super state
            super.onRestoreInstanceState(state.getParcelable(KEY_SUPER_STATE))
            
            // Restore our state
            state.getString(KEY_POSITION)?.let { positionName ->
                try {
                    currentPosition = IAMPosition.valueOf(positionName)
                } catch (e: IllegalArgumentException) {
                    PELogger.error("Error restoring IAM position: ${e.message}", e)
                }
            }
            contentHeight = state.getInt(KEY_CONTENT_HEIGHT)
            lastSavedScrollPosition = state.getInt(KEY_SCROLL_POSITION)
            lastHtmlContent = state.getString(KEY_HTML_CONTENT)
            
            // Update layout with restored state
            currentPosition?.let { position ->
                updateLayout(position, contentHeight)
            }
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private fun createTopLayoutParams(): LayoutParams {
        // Render top banners as a contained, floating card (matching the dashboard
        // preview) rather than full-bleed: margins on ALL sides and a max width
        // cap. Height wraps content; the far-side (bottom) margin doubles as a
        // max-height constraint in FrameLayout, so a tall/scrollable card keeps a
        // symmetric gap instead of running flush to the screen edge.
        val margin = dpToPx(16)
        return LayoutParams(
            bannerWidthPx(margin),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = statusBarOverlapPx() + margin
            bottomMargin = navigationBarOverlapPx() + margin
            leftMargin = margin
            rightMargin = margin
        }
    }

    private fun createBottomLayoutParams(): LayoutParams {
        // Render bottom banners as a contained, floating card (see createTopLayoutParams).
        val margin = dpToPx(16)
        return LayoutParams(
            bannerWidthPx(margin),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            topMargin = statusBarOverlapPx() + margin
            bottomMargin = navigationBarOverlapPx() + margin
            leftMargin = margin
            rightMargin = margin
        }
    }

    /**
     * Width for a contained banner card: full width minus side margins, capped at
     * a max so it doesn't stretch edge-to-edge on wide screens/tablets.
     */
    private fun bannerWidthPx(margin: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = dpToPx(560)
        return Math.min(screenWidth - 2 * margin, maxWidth)
    }

    private fun createCenterLayoutParams(): LayoutParams {
        // Maximum height based on screen orientation (like iOS implementation).
        // Shared with the post-measure resize in the JS bridge so both passes cap
        // identically — see centerMaxHeightPx().
        val maxHeight = centerMaxHeightPx()

        // For center messages, use initial WRAP_CONTENT
        return LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (contentHeight > 0) {
                // Ensure we respect the max height limit
                Math.min(contentHeight, maxHeight)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        ).apply {
            gravity = Gravity.CENTER

            // Standard 16dp margins on all sides. Vertical margins (inside the
            // status/nav-bar insets) also cap the height in FrameLayout, so a
            // tall modal keeps a symmetric gap instead of touching the screen
            // edges — consistent with the top/bottom banner framing.
            val margin = dpToPx(16)
            leftMargin = margin
            rightMargin = margin
            topMargin = getStatusBarHeight() + margin
            bottomMargin = getNavigationBarHeight() + margin

            // Landscape mode needs additional width constraints
            val dm = resources.displayMetrics
            if (dm.widthPixels > dm.heightPixels) {
                // In landscape, limit width to 70% of screen width like iOS
                width = (dm.widthPixels * 0.7).toInt()
            }
        }
    }

    private fun createFullScreenLayoutParams(): LayoutParams {
        // Use a concrete full-window pixel height rather than MATCH_PARENT. The
        // HTML loads before the view is attached/measured, so with MATCH_PARENT
        // the page's `100vh` resolves against a not-yet-sized viewport and the
        // gradient collapses to the content's natural height at the top. A
        // fixed screen-height gives `100vh` a definite basis so the takeover
        // fills the window.
        return LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            fullScreenHeightPx()
        ).apply {
            // Keep the takeover below the status bar when the anchor view
            // extends under it (edge-to-edge hosts, e.g. Flutter). Zero here
            // pre-attach; re-applied with real insets in
            // reapplyPositionInsetMargins.
            topMargin = statusBarOverlapPx()
        }
    }

    /**
     * Full height in pixels for FULL messages — the height of the region the
     * overlay is anchored to (the activity content view), minus any part of
     * that region covered by the system bars (see statusBarOverlapPx /
     * navigationBarOverlapPx). Prefers the attached parent's height; falls
     * back to the display height. MATCH_PARENT (-1) is returned only if
     * neither is known yet.
     */
    private fun fullScreenHeightPx(): Int {
        (parent as? View)?.height?.takeIf { it > 0 }?.let {
            return it - statusBarOverlapPx() - navigationBarOverlapPx()
        }
        val display = resources.displayMetrics.heightPixels
        return if (display > 0) display else ViewGroup.LayoutParams.MATCH_PARENT
    }

    /**
     * Pixels of the status bar that cover the TOP of the anchor (parent) view.
     * Zero in classic themed hosts, where the activity content view starts
     * below the status bar (native example, React Native); positive in
     * edge-to-edge hosts, where the content view spans the whole window
     * (e.g. Flutter) and an un-offset FULL takeover would draw under the
     * status bar.
     */
    private fun statusBarOverlapPx(): Int {
        val anchor = parent as? View ?: return 0
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        return (statusBarHeight - location[1]).coerceIn(0, statusBarHeight)
    }

    /**
     * Pixels of the navigation bar that cover the BOTTOM of the anchor
     * (parent) view. Counterpart of statusBarOverlapPx.
     */
    private fun navigationBarOverlapPx(): Int {
        val anchor = parent as? View ?: return 0
        val rootHeight = anchor.rootView?.height?.takeIf { it > 0 } ?: return 0
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorBottom = location[1] + anchor.height
        val navigationBarTop = rootHeight - navigationBarHeight
        return (anchorBottom - navigationBarTop).coerceIn(0, navigationBarHeight)
    }

    /**
     * Re-applies the system-bar offsets after window insets are dispatched.
     * updateLayout() runs before the container is attached, so at that point
     * getStatusBarHeight()/getNavigationBarHeight() are still 0; this keeps
     * TOP/BOTTOM banners clear of the system bars and re-fits FULL takeovers
     * once the real insets arrive. FULL matters in edge-to-edge hosts (e.g.
     * Flutter), where the anchor view extends under the status bar and the
     * takeover must be offset/shrunk by the overlap. CENTER needs no
     * adjustment (centered, capped at half the screen).
     */
    private fun reapplyPositionInsetMargins() {
        val wv = webView ?: return
        val lp = wv.layoutParams as? LayoutParams ?: return
        when (currentPosition) {
            // Banners keep their 16dp card margin on top of the bar overlap
            // (see createTopLayoutParams). Overlap, not the raw inset: in a
            // classic host the content view already starts below the status
            // bar, so adding the inset would double it; in an edge-to-edge host
            // (Android 15+ at targetSdk 35+, Flutter) it is the full bar height.
            IAMPosition.TOP -> {
                val top = statusBarOverlapPx() + dpToPx(16)
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    wv.layoutParams = lp
                }
            }
            IAMPosition.BOTTOM -> {
                val bottom = navigationBarOverlapPx() + dpToPx(16)
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom
                    wv.layoutParams = lp
                }
            }
            IAMPosition.FULL -> {
                val topOverlap = statusBarOverlapPx()
                val height = fullScreenHeightPx()
                if (lp.topMargin != topOverlap || lp.height != height) {
                    lp.topMargin = topOverlap
                    lp.height = height
                    wv.layoutParams = lp
                }
            }
            else -> {}
        }
    }

    private fun getStatusBarHeight(): Int {
        return statusBarHeight
    }

    private fun getNavigationBarHeight(): Int {
        return navigationBarHeight
    }

    private fun dpToPx(dp: Int): Int {
        val density = context?.resources?.displayMetrics?.density ?: 1.0f
        return (dp * density).toInt()
    }

    /**
     * A CSS pixel reported by the page equals one dp, so converting to device pixels
     * is the same density multiply. Kept separate from [dpToPx] because the input is
     * a fractional measurement, not an integer dp constant.
     */
    private fun cssPxToDevicePx(cssPx: Float): Int {
        val density = context?.resources?.displayMetrics?.density ?: 1.0f
        return Math.round(cssPx * density)
    }

    /**
     * Max height for a `center` modal, in device pixels: 50% of the screen in
     * portrait, 60% in landscape. Single source of truth — both the initial layout
     * pass and the post-measure resize must apply it, or whichever runs last wins.
     */
    private fun centerMaxHeightPx(): Int {
        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        return (dm.heightPixels * if (isLandscape) 0.6 else 0.5).toInt()
    }

    /**
     * Sets up gesture handling for the container
     */
    private fun setupGestureHandling(position: IAMPosition) {
        // Remove existing gesture handler
        gestureHandler?.cleanup()
        gestureHandler = null

        // cleanup() nulls the context when the container detaches; a posted
        // updateLayout (e.g. from measureContentHeight's view.post) can still
        // run after that — it must be a no-op, not an NPE in the host app.
        val ctx = context ?: return

        // Create new gesture handler
        gestureHandler = IAMGestureHandler(ctx, this).apply {
            setOnDismissListener { dismissListener?.invoke() }
        }
    }

    // JavaScript interface for WebView communication
    private inner class JsBridge {
        @JavascriptInterface
        fun onContentHeightMeasured(height: Float) {
            PELogger.debug("JsBridge: Content height measured: $height")
            
            // Need to run on UI thread
            post {
                if (height <= 0) {
                    PELogger.debug("JsBridge: Ignoring invalid height: $height")
                    return@post
                }
                
                PELogger.debug("JsBridge: Updating WebView height to $height")
                
                // Make sure height is at least a minimum value (50dp)
                val minHeight = dpToPx(50)
                // The page reports CSS pixels; every layout height below is in device
                // pixels. Convert here so `contentHeight` is px everywhere downstream —
                // on a 2.625-density screen the two differ by 2.6x, which silently
                // broke the CENTER max-height comparison.
                val intHeight = cssPxToDevicePx(height)

                // Use the measured height if it's valid, otherwise use the minimum height
                val finalHeight = if (intHeight > minHeight) intHeight else minHeight
                
                // For better reliability, always store the largest height we've seen
                // (in case some measurements are partial)
                contentHeight = contentHeight.coerceAtLeast(finalHeight)
                PELogger.debug("JsBridge: Content height now: $contentHeight")
                
                // Update WebView layout parameters based on position
                webView?.let { wv ->
                    // For banners, always stick with WRAP_CONTENT
                    if (currentPosition == IAMPosition.TOP || currentPosition == IAMPosition.BOTTOM) {
                        wv.layoutParams = wv.layoutParams.apply {
                            this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    } else {
                        wv.layoutParams = wv.layoutParams.apply {
                            if (currentPosition == IAMPosition.CENTER) {
                                // CENTER: wrap the modal content but keep the 50/60%
                                // cap. WRAP_CONTENT here used to discard the bound set
                                // by createCenterLayoutParams — and WRAP_CONTENT on
                                // content taller than the screen resolves to the full
                                // parent height, so a tall modal grew to ~93% of the
                                // screen. Apply the cap explicitly instead.
                                this.height = Math.min(contentHeight, centerMaxHeightPx())
                            } else {
                                // FULL: fill the whole window for a true
                                // full-screen takeover. Do NOT shrink to the
                                // measured content height, or the message
                                // collapses into a small box at the top.
                                this.height = fullScreenHeightPx()
                            }
                        }
                    }
                    
                    // Ensure visibility after sizing
                    wv.visibility = View.VISIBLE
                }
                
                // Make sure the container is visible
                this@IAMWebViewContainer.visibility = View.VISIBLE

                // Force a layout update
                requestLayout()
                invalidate()

                // Content taller than the screen must stay reachable
                ensureScrollableIfClipped()
            }
        }

        @JavascriptInterface
        fun handleAction(actionId: String) {
            PELogger.debug("JsBridge: Action received from WebView: $actionId")
            try {
                PELogger.debug("JsBridge: Attempting to handle action on UI thread")
                post {
                    // The documented link-out route is an open_url ACTION through this
                    // bridge, and it reached the same ACTION_VIEW as a tapped link with no
                    // gesture check at all — a load handler calling handleAction on its own
                    // open_url button ejected the user from the app. Same rule as the
                    // navigation gate: something the user did has to stand behind it.
                    if (!userGestureBehind()) {
                        PELogger.debug("JsBridge: refusing action $actionId — no user gesture behind it (script-initiated)")
                        return@post
                    }
                    consumeGesture()
                    PELogger.debug("JsBridge: Now on UI thread, handling action: $actionId")
                    this@IAMWebViewContainer.handleAction(actionId)
                }
            } catch (e: Exception) {
                PELogger.error("JsBridge: Error handling action", e)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Listen for insets and store them, then re-apply banner offsets.
        // Insets are dispatched asynchronously — after updateLayout may have
        // already run with 0 — so TOP/BOTTOM banners need their status/nav-bar
        // margin re-applied once the real insets arrive.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            reapplyPositionInsetMargins()
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /**
     * Helper method to check if a point is inside a view
     */
    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        
        return (rawX >= x && rawX <= (x + view.width) &&
                rawY >= y && rawY <= (y + view.height))
    }

    // Expose current position for gesture handler
    fun getCurrentPosition(): IAMPosition? {
        return currentPosition
    }

    /**
     * Whether a gesture *outside* the message may dismiss it — the campaign's
     * `shouldDismissOnTap`. False means the message may only be answered by its own
     * buttons, so neither an outside tap nor a fling dismisses it.
     *
     * Held on the container rather than the gesture handler: the handler is recreated
     * when the window re-attaches (rotation), and the flag has to survive that.
     */
    private var dismissOnTap: Boolean = true

    fun setDismissOnTap(enabled: Boolean) {
        dismissOnTap = enabled
    }

    fun isDismissOnTapEnabled(): Boolean = dismissOnTap

    /**
     * The single dismissal path for a tap that landed **outside** the message, at any
     * position. Both entries — the gesture handler for touch, this view's click
     * listener for a programmatic/accessibility click — call here, so one tap can
     * never invoke the dismiss listener twice.
     *
     * Callers have already established the touch was outside the message: anything
     * inside the WebView is dispatched to it before either entry is reached.
     *
     * @return whether the tap dismissed the message.
     */
    fun onOutsideTap(): Boolean {
        if (!dismissOnTap) {
            PELogger.debug("Outside tap ignored: campaign opted out of dismiss-on-tap")
            return false
        }
        dismissListener?.invoke()
        return true
    }
    
    // Expose WebView for gesture handler
    fun getWebView(): View? {
        return webView
    }

    // Implementation of IAMActionHandlerInterface
    override fun handleAction(actionId: String) {
        PELogger.debug("IAMWebViewContainer: handleAction called with action: $actionId")
        
        // Pass action to listener if available, otherwise try to find parent handler
        if (actionListener != null) {
            PELogger.debug("IAMWebViewContainer: Using direct action listener")
            actionListener?.invoke(actionId)
        } else {
            PELogger.debug("IAMWebViewContainer: Looking for parent action handler")
            // Try to find parent handler (original implementation)
            var parent = parent
            while (parent != null) {
                if (parent is IAMActionHandlerInterface) {
                    PELogger.debug("IAMWebViewContainer: Found parent action handler")
                    (parent as IAMActionHandlerInterface).handleAction(actionId)
                    return
                }
                parent = parent.parent as? ViewGroup
            }
            
            // If no handler found in parent views, try passing to dismissListener
            PELogger.debug("IAMWebViewContainer: No parent action handler found")
            if (actionId == "dismiss_action" || actionId == "close_action") {
                PELogger.debug("IAMWebViewContainer: Using dismiss listener")
                dismissListener?.invoke()
            }
        }
    }
} 