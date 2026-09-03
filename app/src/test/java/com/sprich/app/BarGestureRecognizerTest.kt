package com.sprich.app

import com.sprich.app.input.ime.BarGestureRecognizer
import com.sprich.app.input.ime.BarGestureRecognizer.Action
import com.sprich.app.input.ime.BarGestureRecognizer.Preview
import org.junit.Assert.*
import org.junit.Test

class BarGestureRecognizerTest {
    private fun gesture() = BarGestureRecognizer(8f).apply { down(200f, 60f, 0, 0f) }

    @Test fun shortLongAndSlowLeftSwipesHaveDifferentActions() {
        assertEquals(Action.DELETE_UNIT, gesture().up(140f, 60f, 180).action)
        assertEquals(Action.DELETE_PHRASE, gesture().up(60f, 60f, 180).action)
        assertEquals(Action.DELETE_PHRASE, gesture().up(140f, 60f, 420).action)
    }

    @Test fun aHeldSwipeRepeatsAndReleaseAddsNoDeletion() {
        val g = gesture()
        assertTrue(g.move(60f, 60f, 100).beginDeletion)
        assertNull(g.tick(549).action)
        assertEquals(Action.DELETE_SENTENCE, g.tick(550).action)
        assertEquals(Action.DELETE_SENTENCE, g.tick(1100).action)
        assertNull(g.up(60f, 60f, 1150).action)
        assertNull(g.tick(5000).action)
        assertNull(g.nextDeadline)
    }

    @Test fun retractingOrLeavingTheAxisCancelsDeletion() {
        for ((x, y) in listOf(190f to 60f, 50f to 230f)) {
            val g = gesture()
            g.move(60f, 60f, 100)
            g.move(x, y, 200)
            assertNull(g.up(60f, 60f, 300).action)
            assertNull(g.tick(3000).action)
        }
    }

    @Test fun upwardSwitchIsImmediateOnlyAfterLeavingTheBar() {
        val g = gesture()
        assertNull(g.move(200f, 10f, 100).action)
        assertEquals(Action.SWITCH_KEYBOARD, g.move(200f, -2f, 140).action)
        assertNull(g.up(200f, -80f, 200).action)
    }

    @Test fun downAndRightCommitOnRelease() {
        val down = gesture()
        assertEquals(Preview.HIDE, down.move(200f, 120f, 120).preview)
        assertEquals(Action.HIDE, down.up(200f, 120f, 180).action)
        val right = gesture()
        assertEquals(Preview.WHISPER, right.move(270f, 60f, 120).preview)
        assertEquals(Action.WHISPER, right.up(270f, 60f, 180).action)
    }

    @Test fun stationaryHoldOpensSettingsWithoutATapOnRelease() {
        val g = gesture()
        assertNull(g.tick(2499).action)
        assertEquals(Action.SETTINGS, g.tick(2500).action)
        assertNull(g.up(200f, 60f, 2600).action)
    }

    @Test fun cancellationDiagonalsAndOutAndBackMovementNeverBecomeTaps() {
        val cancelled = gesture(); cancelled.move(60f, 60f, 100); cancelled.cancel()
        assertNull(cancelled.up(60f, 60f, 300).action)
        assertNull(cancelled.tick(4000).action)
        assertNull(gesture().up(100f, 160f, 200).action)
        val moved = gesture(); moved.move(210f, 70f, 100)
        assertNull(moved.up(200f, 60f, 150).action)
        assertEquals(Action.TAP, gesture().up(201f, 60f, 100).action)
    }

    @Test fun slowSwipePreviewChangesBeforeRelease() {
        val g = gesture()
        assertEquals(Preview.DELETE_UNIT, g.move(140f, 60f, 100).preview)
        assertEquals(350L, g.nextDeadline)
        assertEquals(Preview.DELETE_SENTENCE, g.tick(350).preview)
    }
}
