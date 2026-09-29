package com.pushengage.pushengage.iam.display

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.pushengage.pushengage.iam.model.IAMPosition

/**
 * Utility class for handling in-app message animations
 */
internal object IAMAnimationUtil {
    private const val ANIMATION_DURATION = 300L
    private const val SCALE_START = 0.8f
    private const val SCALE_END = 1.0f
    private const val ALPHA_START = 0.0f
    private const val ALPHA_END = 1.0f

    /**
     * Animates the entry of a message
     */
    fun animateEntry(view: View, position: IAMPosition, onAnimationEnd: () -> Unit = {}) {
        when (position) {
            IAMPosition.TOP -> animateSlideIn(view, SlideDirection.TOP, onAnimationEnd)
            IAMPosition.BOTTOM -> animateSlideIn(view, SlideDirection.BOTTOM, onAnimationEnd)
            IAMPosition.CENTER -> animateScale(view, true, onAnimationEnd)
            IAMPosition.FULL -> animateFade(view, true, onAnimationEnd)
        }
    }

    /**
     * Animates the exit of a message
     */
    fun animateExit(view: View, position: IAMPosition, onAnimationEnd: () -> Unit = {}) {
        when (position) {
            IAMPosition.TOP -> animateSlideOut(view, SlideDirection.TOP, onAnimationEnd)
            IAMPosition.BOTTOM -> animateSlideOut(view, SlideDirection.BOTTOM, onAnimationEnd)
            IAMPosition.CENTER -> animateScale(view, false, onAnimationEnd)
            IAMPosition.FULL -> animateFade(view, false, onAnimationEnd)
        }
    }

    private enum class SlideDirection {
        TOP, BOTTOM
    }

    private fun animateSlideIn(view: View, direction: SlideDirection, onAnimationEnd: () -> Unit) {
        // Calculate translation based on direction
        val translationY = when (direction) {
            SlideDirection.TOP -> -view.height.toFloat()
            SlideDirection.BOTTOM -> view.height.toFloat()
        }

        // Set initial position
        view.translationY = translationY
        view.alpha = ALPHA_START
        view.visibility = View.VISIBLE

        // Create and start animation
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, translationY, 0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, ALPHA_START, ALPHA_END)
        )

        animator.apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            addListener(createAnimatorListener(view, onAnimationEnd))
            start()
        }
    }

    private fun animateSlideOut(view: View, direction: SlideDirection, onAnimationEnd: () -> Unit) {
        // Calculate translation based on direction
        val translationY = when (direction) {
            SlideDirection.TOP -> -view.height.toFloat()
            SlideDirection.BOTTOM -> view.height.toFloat()
        }

        // Create and start animation
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, view.translationY, translationY),
            PropertyValuesHolder.ofFloat(View.ALPHA, view.alpha, ALPHA_START)
        )

        animator.apply {
            duration = ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addListener(createAnimatorListener(view, onAnimationEnd))
            start()
        }
    }

    private fun animateScale(view: View, isEntry: Boolean, onAnimationEnd: () -> Unit) {
        // Set initial values for entry animation
        if (isEntry) {
            view.scaleX = SCALE_START
            view.scaleY = SCALE_START
            view.alpha = ALPHA_START
            view.visibility = View.VISIBLE
        }

        // Create scale animation
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, if (isEntry) SCALE_START else SCALE_END, if (isEntry) SCALE_END else SCALE_START),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, if (isEntry) SCALE_START else SCALE_END, if (isEntry) SCALE_END else SCALE_START),
            PropertyValuesHolder.ofFloat(View.ALPHA, if (isEntry) ALPHA_START else ALPHA_END, if (isEntry) ALPHA_END else ALPHA_START)
        )

        animator.apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            addListener(createAnimatorListener(view, onAnimationEnd))
            start()
        }
    }

    private fun animateFade(view: View, isEntry: Boolean, onAnimationEnd: () -> Unit) {
        // Set initial values for entry animation
        if (isEntry) {
            view.alpha = ALPHA_START
            view.visibility = View.VISIBLE
        }

        // Create fade animation
        val animator = ObjectAnimator.ofFloat(
            view,
            View.ALPHA,
            if (isEntry) ALPHA_START else ALPHA_END,
            if (isEntry) ALPHA_END else ALPHA_START
        )

        animator.apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            addListener(createAnimatorListener(view, onAnimationEnd))
            start()
        }
    }

    private fun createAnimatorListener(view: View, onAnimationEnd: () -> Unit) = object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animation: Animator) {
            // Promote the animated view (which hosts a WebView) to a hardware
            // layer so alpha/scale/translation frames composite a cached texture
            // instead of re-rendering web content every frame.
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onAnimationEnd(animation: Animator) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            onAnimationEnd()
        }
    }
} 