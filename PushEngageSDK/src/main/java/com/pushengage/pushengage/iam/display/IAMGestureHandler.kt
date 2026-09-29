package com.pushengage.pushengage.iam.display

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMPosition

/**
 * Handles gesture detection for in-app messages
 */
internal class IAMGestureHandler(
    private val context: Context,
    private val container: IAMWebViewContainer
) {
    private var dismissListener: (() -> Unit)? = null
    private var gestureDetector: GestureDetector? = null
    private var velocityTracker: VelocityTracker? = null

    init {
        setupGestureDetector()
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // The container only reaches us for a touch outside the message, so
                // this is the outside tap: `shouldDismissOnTap` decides, at every
                // position. It used to also require CENTER, which left a banner or
                // full-screen campaign undismissable however it was authored.
                return container.onOutsideTap()
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // A fling is also a dismissal that bypasses the message's own
                // buttons, so the same opt-out applies.
                if (!container.isDismissOnTapEnabled()) {
                    return false
                }

                val position = container.getCurrentPosition()

                // No fling dismissal for banners (TOP/BOTTOM)
                if (position == IAMPosition.TOP || position == IAMPosition.BOTTOM) {
                    return false
                }
                
                // Handle fling for other positions
                if (e1 != null) {
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x

                    // Detect horizontal fling
                    if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(velocityX) > 1000) {
                        PELogger.debug("Horizontal fling detected - dismissing")
                        dismissListener?.invoke()
                        return true
                    }

                    // Detect vertical fling
                    if (Math.abs(deltaY) > 150 && Math.abs(velocityY) > 1000) {
                        // For CENTER messages, allow both up and down fling
                        if (position == IAMPosition.CENTER) {
                            PELogger.debug("Vertical fling detected for modal - dismissing")
                            dismissListener?.invoke()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    fun setOnDismissListener(listener: () -> Unit) {
        dismissListener = listener
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        // No hit test and no position check: the container dispatches anything inside
        // the WebView to the WebView before calling us, so everything arriving here is
        // already an outside touch. This used to re-derive the hit test for banners
        // and swallow the result, a second copy of the gate in the container.
        return gestureDetector?.onTouchEvent(event) ?: false
    }

    fun cleanup() {
        dismissListener = null
        gestureDetector = null
        velocityTracker?.recycle()
        velocityTracker = null
    }

}