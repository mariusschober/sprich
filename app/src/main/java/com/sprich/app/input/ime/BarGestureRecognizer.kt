package com.sprich.app.input.ime

import kotlin.math.abs
import kotlin.math.hypot

/** One pointer, one direction. Time and coordinates are supplied so cancellation is deterministic. */
internal class BarGestureRecognizer(private val slop: Float) {
    enum class Action { TAP, DELETE_UNIT, DELETE_PHRASE, DELETE_SENTENCE, WHISPER, SWITCH_KEYBOARD, HIDE, SETTINGS }
    enum class Preview { NONE, DELETE_UNIT, DELETE_SENTENCE, REPEAT, WHISPER, HIDE }
    data class Update(val action: Action? = null, val preview: Preview = Preview.NONE, val beginDeletion: Boolean = false)
    private enum class Axis { HORIZONTAL, VERTICAL }

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var top = 0f
    private var startedAt = 0L
    private var dx = 0f
    private var dy = 0f
    private var axis: Axis? = null
    private var moved = false
    private var deleteClaimed = false
    private var rightClaimed = false
    private var repeated = false
    private var holdAt: Long? = null
    private var stableX = 0f
    private var stableY = 0f
    private var repeatAt: Long? = null
    private var now = 0L

    val nextDeadline: Long?
        get() = when {
            !active -> null
            !moved -> startedAt + SETTINGS_MS
            deleteClaimed && holdAt != null -> repeatAt ?: (holdAt!! + HOLD_MS)
            deleteClaimed && now < startedAt + SLOW_MS -> startedAt + SLOW_MS
            else -> null
        }

    fun down(x: Float, y: Float, time: Long, barTop: Float) {
        cancel()
        active = true; startX = x; startY = y; top = barTop; startedAt = time; now = time
    }

    fun move(x: Float, y: Float, time: Long): Update {
        if (!active) return Update()
        now = time; dx = x - startX; dy = y - startY
        val ax = abs(dx); val ay = abs(dy)
        if (hypot(dx, dy) > slop) moved = true
        if (axis == null && moved) axis = when {
            ax >= ay * 1.4f -> Axis.HORIZONTAL
            ay >= ax * 1.4f -> Axis.VERTICAL
            else -> null
        }
        if (axis == Axis.VERTICAL && ay >= ax * 1.4f) {
            if (dy <= -VERTICAL_DP && y < top) return finish(Action.SWITCH_KEYBOARD)
            return Update(preview = if (dy >= VERTICAL_DP) Preview.HIDE else Preview.NONE)
        }
        if (axis != Axis.HORIZONTAL || ax < ay * 1.4f) {
            // Leaving the locked horizontal direction cancels an armed deletion.
            if (deleteClaimed || rightClaimed) cancel()
            return Update()
        }
        if ((deleteClaimed && dx > -SHORT_DP) || (rightClaimed && dx < RIGHT_DP)) {
            cancel()
            return Update()
        }
        if (dx >= RIGHT_DP && !deleteClaimed) {
            rightClaimed = true
            return Update(preview = Preview.WHISPER)
        }
        if (dx > -SHORT_DP || rightClaimed) return Update()
        val first = !deleteClaimed
        deleteClaimed = true
        if (dx <= -LONG_DP) {
            if (holdAt == null || hypot(x - stableX, y - stableY) > HOLD_SLOP_DP) {
                holdAt = time; stableX = x; stableY = y; repeatAt = null
            }
        } else {
            holdAt = null; repeatAt = null
            if (repeated) { cancel(); return Update() }
        }
        return Update(preview = preview(), beginDeletion = first)
    }

    fun tick(time: Long): Update {
        if (!active) return Update()
        now = time
        if (!moved && time - startedAt >= SETTINGS_MS) return finish(Action.SETTINGS)
        if (deleteClaimed && holdAt != null && time >= (repeatAt ?: (holdAt!! + HOLD_MS))) {
            repeated = true
            repeatAt = time + REPEAT_MS
            return Update(Action.DELETE_SENTENCE, Preview.REPEAT)
        }
        return Update(preview = preview())
    }

    fun up(x: Float, y: Float, time: Long): Update {
        if (!active) return Update()
        val update = move(x, y, time)
        if (update.action != null || !active) return update
        val action = when {
            repeated -> null
            deleteClaimed -> if (-dx >= LONG_DP || time - startedAt >= SLOW_MS) Action.DELETE_PHRASE else Action.DELETE_UNIT
            rightClaimed -> Action.WHISPER
            axis == Axis.VERTICAL && dy >= VERTICAL_DP && abs(dy) >= abs(dx) * 1.4f -> Action.HIDE
            !moved && time - startedAt >= SETTINGS_MS -> Action.SETTINGS
            !moved -> Action.TAP
            else -> null
        }
        return finish(action).copy(beginDeletion = update.beginDeletion)
    }

    fun cancel() {
        active = false; axis = null; moved = false; deleteClaimed = false; rightClaimed = false
        repeated = false; holdAt = null; repeatAt = null; dx = 0f; dy = 0f
    }

    private fun preview() = when {
        repeated -> Preview.REPEAT
        deleteClaimed && (-dx >= LONG_DP || now - startedAt >= SLOW_MS) -> Preview.DELETE_SENTENCE
        deleteClaimed -> Preview.DELETE_UNIT
        rightClaimed -> Preview.WHISPER
        else -> Preview.NONE
    }

    private fun finish(action: Action?): Update { cancel(); return Update(action) }

    companion object {
        const val SHORT_DP = 36f
        const val LONG_DP = 112f
        const val RIGHT_DP = 48f
        const val VERTICAL_DP = 48f
        const val SLOW_MS = 350L
        const val HOLD_MS = 450L
        const val REPEAT_MS = 550L
        const val SETTINGS_MS = 2500L
        private const val HOLD_SLOP_DP = 6f
    }
}
