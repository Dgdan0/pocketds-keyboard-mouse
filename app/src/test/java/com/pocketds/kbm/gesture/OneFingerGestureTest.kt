package com.pocketds.kbm.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One finger: moving the cursor, and tapping to click.
 *
 * Most of these describe behaviour the trackpad already had, written so that
 * pulling the logic out of the views cannot quietly change it. Two describe
 * behaviour we decided was wrong — jitter swallowing a tap, and a long hold
 * still clicking — and assert the fix instead.
 */
class OneFingerGestureTest {

    private val config = GestureConfig(
        cursorSensitivity = 2f,
        scrollSensitivity = 3f,
        tapSlopPx = 10f,
        tapMaxDurationMs = 500L,
        scrollStartSlopPx = 8f
    )

    private fun script() = TouchScript(config)

    @Test
    fun `a drag moves the cursor by the scaled delta`() {
        val s = script()
        s.down(100f, 100f)

        assertEquals(
            listOf(GestureCommand.MoveCursor(dx = 20f, dy = 10f)),
            s.moveBy(0, 10f, 5f)
        )
    }

    @Test
    fun `movement is reported per event, not accumulated from the start`() {
        val s = script()
        s.down(0f, 0f)
        s.moveBy(0, 10f, 0f)

        // The second move travelled 5px; it must not re-report the first 10.
        assertEquals(
            listOf(GestureCommand.MoveCursor(dx = 10f, dy = 0f)),
            s.moveBy(0, 5f, 0f)
        )
    }

    @Test
    fun `movement follows the tracked pointer rather than whichever is listed first`() {
        val s = script()
        s.down(100f, 100f, id = 7)

        assertEquals(
            listOf(GestureCommand.MoveCursor(dx = 20f, dy = 0f)),
            s.moveBy(7, 10f, 0f)
        )
    }

    @Test
    fun `a drag does not also click when the finger lifts`() {
        val s = script()
        s.down(0f, 0f)
        s.moveBy(0, 80f, 0f)

        assertTrue("a drag must not leave a click behind, got ${s.all}", s.up().isEmpty())
    }

    @Test
    fun `a brief still touch clicks`() {
        val s = script()
        s.down(50f, 50f)

        assertEquals(listOf(GestureCommand.LeftClick), s.up(afterMs = 80))
    }

    @Test
    fun `a tap survives slow jitter inside the slop`() {
        // The finger never leaves a few pixels, but the wandering path adds up
        // to far more than the slop. Accumulated travel used to eat this tap.
        val s = script()
        s.down(50f, 50f)
        repeat(10) {
            s.moveTo(0, 53f, 50f, afterMs = 8)
            s.moveTo(0, 50f, 53f, afterMs = 8)
        }

        assertEquals(listOf(GestureCommand.LeftClick), s.up(afterMs = 8))
    }

    @Test
    fun `an excursion that returns to where it started is not a tap`() {
        // Net displacement is zero, so displacement alone would call this a tap.
        // It reached 60px away, which is a drag.
        val s = script()
        s.down(100f, 100f)
        s.moveTo(0, 160f, 100f)
        s.moveTo(0, 100f, 100f)

        assertTrue("a 60px excursion is not a tap, got ${s.all}", s.up().isEmpty())
    }

    @Test
    fun `holding still past the tap timeout does not click`() {
        val s = script()
        s.down(50f, 50f)

        assertTrue(
            "resting a finger for 5s must not click, got ${s.all}",
            s.up(afterMs = 5_000).isEmpty()
        )
    }

    @Test
    fun `a cancelled touch never becomes a click`() {
        val s = script()
        s.down(50f, 50f)

        assertTrue("a cancelled touch must not click, got ${s.all}", s.cancel(afterMs = 20).isEmpty())
    }

    @Test
    fun `a move with no preceding down anchors silently`() {
        // Split's revealed trackpad begins intercepting mid-gesture, so its
        // first event is a MOVE. Reporting that position as travel would fling
        // the cursor across the screen.
        val s = script()

        assertTrue("the anchoring move must emit nothing", s.moveWithoutDown(100f, 100f).isEmpty())
        assertEquals(
            listOf(GestureCommand.MoveCursor(dx = 20f, dy = 0f)),
            s.moveBy(0, 10f, 0f)
        )
    }

    @Test
    fun `sensitivity is taken from the config`() {
        val s = TouchScript(config.copy(cursorSensitivity = 1f))
        s.down(0f, 0f)

        assertEquals(
            listOf(GestureCommand.MoveCursor(dx = 10f, dy = 0f)),
            s.moveBy(0, 10f, 0f)
        )
    }
}
