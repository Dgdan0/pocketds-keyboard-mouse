package com.pocketds.kbm.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Double-tap and hold, then drag: selecting text on the other screen.
 *
 * Right-clicking works and the cursor gestures work, so selection is the
 * missing half of editing up there.
 *
 * The whole difficulty is that this starts exactly like a double-click, and
 * both have to keep working. Which one it is only becomes clear after the
 * second finger is already down — either it moves, or it lets go.
 */
class DragSelectGestureTest {

    private val config = GestureConfig(
        cursorSensitivity = 1f,
        scrollSensitivity = 3f,
        tapSlopPx = 10f,
        tapMaxDurationMs = 500L,
        scrollStartSlopPx = 8f
    )

    private fun script() = TouchScript(config)

    /** Tap, then press again without letting go. */
    private fun doubleTapHold(): TouchScript {
        val s = script()
        s.down(100f, 100f)
        s.up(afterMs = 60)
        s.down(100f, 100f)
        return s
    }

    @Test
    fun `two quick taps stay two clicks`() {
        // Double-click still has to work: it is how you select a word.
        val s = script()
        s.down(100f, 100f)
        s.up(afterMs = 60)
        s.down(100f, 100f)
        s.up(afterMs = 60)

        assertEquals(
            listOf(GestureCommand.LeftClick, GestureCommand.LeftClick),
            s.all.filterIsInstance<GestureCommand.LeftClick>()
                .map { it as GestureCommand }
        )
        assertTrue("no drag from two taps, got ${s.all}", s.all.none { it is GestureCommand.DragStart })
    }

    @Test
    fun `holding the second tap and moving starts a drag`() {
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)

        assertTrue("expected a drag, got ${s.all}", s.all.any { it is GestureCommand.DragStart })
    }

    @Test
    fun `the drag starts before the movement is delivered`() {
        // Otherwise the first stretch of the selection is lost: the pointer
        // would move with nothing held down.
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)

        val startIndex = s.all.indexOfFirst { it is GestureCommand.DragStart }
        val moveIndex = s.all.indexOfFirst { it is GestureCommand.MoveCursor }
        assertTrue("drag must come first, got ${s.all}", startIndex in 0 until moveIndex)
    }

    @Test
    fun `dragging moves the cursor while held`() {
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)
        s.moveTo(0, 160f, 100f, afterMs = 30)

        val moved = s.all.filterIsInstance<GestureCommand.MoveCursor>().sumOf { it.dx.toDouble() }
        assertEquals(60.0, moved, 0.01)
    }

    @Test
    fun `letting go ends the drag`() {
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)
        s.up(afterMs = 30)

        assertTrue("expected DragEnd, got ${s.all}", s.all.contains(GestureCommand.DragEnd))
    }

    @Test
    fun `a drag does not also click on release`() {
        // A click at the end would collapse the selection that was just made.
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)
        s.up(afterMs = 30)

        val afterStart = s.all.dropWhile { it !is GestureCommand.DragStart }
        assertTrue("got $afterStart", afterStart.none { it == GestureCommand.LeftClick })
    }

    @Test
    fun `a slow second press is not a drag`() {
        // Two taps far apart in time are two separate taps, not a double.
        val s = script()
        s.down(100f, 100f)
        s.up(afterMs = 60)
        // The delay has to sit before the press, not after it: it is the gap
        // between the two taps that decides whether they are a pair.
        s.down(100f, 100f, id = 0, afterMs = 900)
        s.moveTo(0, 140f, 100f, afterMs = 30)

        assertTrue("too slow to be a double-tap, got ${s.all}", s.all.none { it is GestureCommand.DragStart })
    }

    @Test
    fun `a second press somewhere else is not a drag`() {
        val s = script()
        s.down(100f, 100f)
        s.up(afterMs = 60)
        s.down(400f, 400f)
        s.moveTo(0, 440f, 400f, afterMs = 30)

        assertTrue("moved too far between taps, got ${s.all}", s.all.none { it is GestureCommand.DragStart })
    }

    @Test
    fun `a second finger during a drag does not start scrolling`() {
        // Resting a thumb mid-selection must not turn it into a scroll.
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)
        s.pointerDown(1, 300f, 300f)
        s.moveAllBy(0f, -40f)

        assertTrue("got ${s.all}", s.all.none { it is GestureCommand.Scroll })
    }

    @Test
    fun `tearing down mid-drag releases it`() {
        // Otherwise the synthetic finger stays pressed on the other screen.
        val s = doubleTapHold()
        s.moveTo(0, 140f, 100f, afterMs = 30)

        assertFalse(s.all.contains(GestureCommand.DragEnd))
        assertEquals(listOf(GestureCommand.DragEnd), s.reset())
    }
}
