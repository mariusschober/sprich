package com.sprich.app.input.ime

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout

/** Owns the whole touch sequence, including starts over a button and moves beyond the bar. */
internal class VoiceGestureBar(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val recognizer = BarGestureRecognizer(ViewConfiguration.get(context).scaledTouchSlop / density)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    var onAction: (BarGestureRecognizer.Action) -> Boolean = { false }
    var onPreview: (BarGestureRecognizer.Preview) -> Unit = {}
    var onBeginDeletion: () -> Unit = {}
    var onFinishGesture: () -> Unit = {}
    var tapTargets: List<View> = emptyList()
    private var tapTarget: View? = null
    private var previousPreview = BarGestureRecognizer.Preview.NONE
    private var touching = false
    private val timer = object : Runnable {
        override fun run() { apply(recognizer.tick(SystemClock.uptimeMillis())); schedule() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (accessibility.isTouchExplorationEnabled) {
            cancelGesture()
            return super.dispatchTouchEvent(event)
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            cancelGesture()
            return true
        }
        val x = event.rawX / density
        val y = event.rawY / density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture()
                touching = true
                val bounds = Rect()
                getGlobalVisibleRect(bounds)
                recognizer.down(x, y, event.eventTime, bounds.top / density)
                tapTarget = tapTargets.firstOrNull { it.isShown && it.isEnabled && it.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt()) }
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> if (touching) apply(recognizer.move(x, y, event.eventTime))
            MotionEvent.ACTION_UP -> if (touching) {
                apply(recognizer.up(x, y, event.eventTime))
                cancelGesture()
            }
        }
        schedule()
        return true
    }

    private fun apply(update: BarGestureRecognizer.Update) {
        if (update.beginDeletion) onBeginDeletion()
        // End the preview before a hide/switch action starts its animation or changes windows.
        if (update.action != null && update.preview == BarGestureRecognizer.Preview.NONE && previousPreview != update.preview) {
            previousPreview = update.preview
            onPreview(update.preview)
        }
        if (update.action == BarGestureRecognizer.Action.TAP) (tapTarget ?: this).performClick()
        else if (update.action != null && !onAction(update.action)) { cancelGesture(); return }
        if (update.preview != previousPreview) {
            previousPreview = update.preview
            onPreview(update.preview)
        }
    }

    private fun schedule() {
        removeCallbacks(timer)
        recognizer.nextDeadline?.let { postDelayed(timer, (it - SystemClock.uptimeMillis()).coerceAtLeast(1)) }
    }

    fun cancelGesture() {
        touching = false; recognizer.cancel(); removeCallbacks(timer); isPressed = false; tapTarget = null
        if (previousPreview != BarGestureRecognizer.Preview.NONE) { previousPreview = BarGestureRecognizer.Preview.NONE; onPreview(previousPreview) }
        onFinishGesture()
    }

    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) cancelGesture()
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE) cancelGesture()
        super.onWindowVisibilityChanged(visibility)
    }
}
