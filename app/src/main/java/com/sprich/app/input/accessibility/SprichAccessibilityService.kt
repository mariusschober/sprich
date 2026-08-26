package com.sprich.app.input.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.view.inputmethod.EditorInfo

/**
 * Experimental accessibility companion.
 * Only active when user explicitly enables in Settings.
 * Detects focused editable nodes, offers overlay voice button, inserts via ACTION_SET_TEXT incrementally.
 * Primary path remains IME; this is fallback when user keeps Gboard.
 */
class SprichAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var isEnabled = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isEnabled) return
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: rootInActiveWindow ?: return
                handleNode(node)
                node.recycle()
            }
        }
    }

    private fun handleNode(node: AccessibilityNodeInfo) {
        // Check editable
        if (!node.isEditable && node.className?.toString()?.contains("EditText") != true) return
        if (node.isPassword) {
            Log.i("SprichA11y", "password node, ignore")
            return
        }
        // Could show overlay affordance near node bounds
        // For v1, just log and rely on overlay view controller
        val text = node.text?.toString() ?: ""
        Log.d("SprichA11y", "editable focused: ${node.className} textLen=${text.length} bounds=${node.getBoundsInScreen(android.graphics.Rect()).let{it}}")
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("SprichA11y", "connected")
    }

    // Safe incremental edit: avoid replacing entire field every hypothesis
    fun insertText(node: AccessibilityNodeInfo, stable: String, unstable: String, isFinal: Boolean) {
        try {
            val composing = if (stable.isNotEmpty() && unstable.isNotEmpty()) "$stable $unstable" else stable.ifEmpty { unstable }
            if (isFinal) {
                // Commit
                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, composing) }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else {
                // For live, we could use setText as well but risk duplication;
                // experimental: use same action but with composing suffix
                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, composing) }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        } catch (e: Exception) {
            Log.w("SprichA11y", "insert failed", e)
        }
    }
}
