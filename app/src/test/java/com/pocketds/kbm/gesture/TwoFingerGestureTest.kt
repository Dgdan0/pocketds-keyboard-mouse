package com.pocketds.kbm.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two fingers: scrolling, and tapping to right-click.
 *
 * The scroll behaviour is what the trackpad already did and must keep doing —
 * notably following one finger rather than averaging both, because real touch
 * hardware does not report two points cleanly. The right-click and the
 * start-slop gate are new.
 */
class TwoFingerGestureTest {

    private val config = GestureConfig(
        cursorSensitivity = 2f,
        scrollSensitivity = 3f,
        tapSlopPx = 10f,
        tapMaxDurationMs = 500L,
        scrollStartSlopPx = 8f
    )

    private fun script() = TouchScript(config)

    /**
     * A gate well below the tap slop, which is the shipping relationship: it
     * leaves a band where a wobble is big enough to start a scroll and still
     * small enough to be a tap. The two have to be reconciled somewhere, and
     * pinning both numbers here says which reconciliation is under test.
     */
    private fun jitteryTapScript() = TouchScript(config.copy(scrollStartSlopPx = 3f, tapSlopPx = 12f))

    /** Two fingers down, ready to scroll. */
    private fun twoFingers(): TouchScript {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f)
        return s
    }

    // --- scrolling --------------------------------------------------------

    @Test
    fun `two fingers moving together scroll at the scroll sensitivity`() {
        val s = twoFingers()

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -30f)),
            s.moveAllBy(0f, -10f)
        )
    }

    @Test
    fun `scrolling follows one finger instead of averaging both`() {
        // The other finger drifting must not dilute the delta.
        val s = twoFingers()
        s.moveTo(1, 200f, 40f, afterMs = 0)

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -30f)),
            s.moveTo(0, 100f, 90f)
        )
    }

    @Test
    fun `two fingers never move the cursor`() {
        val s = twoFingers()
        s.moveAllBy(0f, -10f)
        s.moveAllBy(0f, -10f)

        assertTrue(
            "two fingers must scroll, never move the cursor, got ${s.all}",
            s.all.none { it is GestureCommand.MoveCursor }
        )
    }

    @Test
    fun `movement below the start slop is held back, then delivered in full`() {
        // Two fingers never land at the same instant or hold still, and letting
        // that jitter through becomes a stray tap on the other screen. It is
        // withheld until the gesture is unambiguous, but nothing is discarded.
        val s = twoFingers()

        assertTrue("3px is inside the gate", s.moveAllBy(0f, -3f).isEmpty())
        assertTrue("6px total is still inside the gate", s.moveAllBy(0f, -3f).isEmpty())

        // 3 + 3 + 6 = 12px of travel, all of it accounted for.
        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -36f)),
            s.moveAllBy(0f, -6f)
        )
    }

    @Test
    fun `the first scroll sample after the second finger lands is not a jump`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.moveBy(0, 50f, 0f)
        // The second finger lands far away; movement must be measured from the
        // tracked finger, not from the gap between them.
        s.pointerDown(1, 300f, 300f)

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 30f, dy = 0f)),
            s.moveBy(0, 10f, 0f)
        )
    }

    @Test
    fun `lifting the tracked finger hands over without ending the scroll`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 500f, 400f)
        s.pointerUp(0)

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -30f)),
            s.moveBy(1, 0f, -10f)
        )
        assertTrue(
            "handover must not end the scroll, got ${s.all}",
            s.all.none { it == GestureCommand.ScrollEnd }
        )
    }

    @Test
    fun `handover works with three fingers down`() {
        // The old code assumed exactly two, picking index 0 or 1, so a third
        // finger broke it.
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f)
        s.pointerDown(2, 300f, 100f)
        s.pointerUp(0)

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -30f)),
            s.moveAllBy(0f, -10f)
        )
    }

    @Test
    fun `lifting a finger we were not following changes nothing`() {
        val s = twoFingers()
        s.pointerUp(1)

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -30f)),
            s.moveBy(0, 0f, -10f)
        )
    }

    @Test
    fun `dropping back to one finger keeps scrolling`() {
        val s = twoFingers()
        s.moveAllBy(0f, -10f)
        s.pointerUp(1)
        s.moveBy(0, 0f, -10f)

        assertTrue(
            "the scroll latch is sticky for the rest of the gesture, got ${s.all}",
            s.all.none { it is GestureCommand.MoveCursor }
        )
    }

    @Test
    fun `handover does not impose a second dead zone mid-scroll`() {
        // The start gate exists to swallow the jitter of two fingers landing.
        // Re-arming it when a finger lifts made an established scroll stall
        // again part-way through, which reads as the scroll going heavy.
        val s = twoFingers()
        s.moveAllBy(0f, -20f)
        // The finger being followed is the one that lifts, so the gesture has to
        // hand over to the survivor — without re-arming the gate.
        s.pointerUp(0)

        assertEquals(
            listOf(GestureCommand.Scroll(dx = 0f, dy = -6f)),
            s.moveBy(1, 0f, -2f)
        )
    }

    @Test
    fun `a scroll ends when the last finger lifts`() {
        val s = twoFingers()
        s.moveAllBy(0f, -20f)
        s.pointerUp(1)

        assertEquals(listOf(GestureCommand.ScrollEnd), s.up())
    }

    @Test
    fun `a scroll does not leave a click behind`() {
        val s = twoFingers()
        s.moveAllBy(0f, -50f)
        s.pointerUp(1)
        s.up()

        assertTrue(
            "a scroll must never click, got ${s.all}",
            s.all.none { it == GestureCommand.LeftClick || it == GestureCommand.RightClick }
        )
    }

    @Test
    fun `cancelling a scroll ends it`() {
        val s = twoFingers()
        s.moveAllBy(0f, -20f)

        assertEquals(listOf(GestureCommand.ScrollEnd), s.cancel())
    }

    // --- two-finger tap = right click -------------------------------------

    @Test
    fun `a two-finger tap right-clicks`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f, afterMs = 30)
        s.pointerUp(1, afterMs = 90)
        s.up(afterMs = 20)

        assertEquals(listOf(GestureCommand.RightClick), s.all)
    }

    @Test
    fun `a two-finger tap that jitters inside the gate still right-clicks`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f, afterMs = 30)
        s.moveAllBy(0f, -3f)
        s.pointerUp(1, afterMs = 60)
        s.up(afterMs = 20)

        assertEquals(listOf(GestureCommand.RightClick), s.all)
        assertTrue(
            "jitter must not reach the other screen as a scroll, got ${s.all}",
            s.all.none { it is GestureCommand.Scroll }
        )
    }

    @Test
    fun `a two-finger tap that jitters past the gate still right-clicks`() {
        // Real fingers are never still, and the scroll gate is only a few
        // pixels, so an ordinary tap trips it and emits a scroll. Deciding on
        // the way down ("it scrolled, so it cannot be a tap") throws the
        // right-click away; the whole gesture is only known on lift, and a
        // wobble well inside the tap slop moved nothing the user can see.
        val s = jitteryTapScript()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f, afterMs = 30)
        s.moveAllBy(0f, -6f)
        s.pointerUp(1, afterMs = 60)
        s.up(afterMs = 20)

        assertTrue("expected a right-click, got ${s.all}", s.all.contains(GestureCommand.RightClick))
    }

    @Test
    fun `a jittery two-finger tap closes its scroll before right-clicking`() {
        // A scroll that was started holds a finger down on the other screen.
        // Right-clicking without releasing it first would leave that finger
        // stuck there.
        val s = jitteryTapScript()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f, afterMs = 30)
        s.moveAllBy(0f, -6f)
        s.pointerUp(1, afterMs = 60)
        s.up(afterMs = 20)

        val ending = s.all.filter {
            it == GestureCommand.ScrollEnd || it == GestureCommand.RightClick
        }
        assertEquals(listOf(GestureCommand.ScrollEnd, GestureCommand.RightClick), ending)
    }

    @Test
    fun `a two-finger gesture that scrolled does not right-click`() {
        val s = twoFingers()
        s.moveAllBy(0f, -30f)
        s.pointerUp(1)
        s.up()

        assertTrue(
            "scrolling rules out a right-click, got ${s.all}",
            s.all.none { it == GestureCommand.RightClick }
        )
        assertTrue("it ends the scroll instead", s.all.contains(GestureCommand.ScrollEnd))
    }

    @Test
    fun `a three-finger tap does not right-click`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f)
        s.pointerDown(2, 300f, 100f)
        s.pointerUp(2)
        s.pointerUp(1)
        s.up()

        assertTrue("only two fingers means right-click, got ${s.all}", s.all.isEmpty())
    }

    @Test
    fun `resting a second finger during a long drag does not right-click`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        repeat(6) { s.moveBy(0, 10f, 0f, afterMs = 50) }
        s.pointerDown(1, 300f, 100f, afterMs = 50)
        s.pointerUp(1, afterMs = 50)
        s.up(afterMs = 20)

        assertTrue(
            "a drag with a brief second finger is not a right-click, got ${s.all}",
            s.all.none { it == GestureCommand.RightClick || it == GestureCommand.LeftClick }
        )
    }

    @Test
    fun `two fingers held too long do not right-click`() {
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f, afterMs = 20)
        s.pointerUp(1, afterMs = 800)
        s.up(afterMs = 20)

        assertTrue("a long two-finger hold is not a tap, got ${s.all}", s.all.isEmpty())
    }

    @Test
    fun `a two-finger tap does not end a scroll that never started`() {
        // Closing a scroll that never opened made the other screen see a
        // touch-down and a lift in the same place, which is a stray tap.
        val s = script()
        s.down(100f, 100f, id = 0)
        s.pointerDown(1, 200f, 100f, afterMs = 30)
        s.pointerUp(1, afterMs = 60)
        s.up(afterMs = 20)

        assertTrue(
            "no scroll started, so there is nothing to end, got ${s.all}",
            s.all.none { it == GestureCommand.ScrollEnd }
        )
    }

    // --- state must not leak between gestures ------------------------------

    @Test
    fun `a tap still works after a scroll`() {
        val s = twoFingers()
        s.moveAllBy(0f, -50f)
        s.pointerUp(1)
        s.up()

        s.down(300f, 300f, id = 0)

        assertEquals(listOf(GestureCommand.LeftClick), s.up(afterMs = 60))
    }

    @Test
    fun `tearing down mid-scroll ends the scroll`() {
        // Without this the synthetic finger stays pressed on the other screen
        // when the panel is dismissed, which this device does on its own.
        val recognizer = TrackpadGestureRecognizer(config)
        recognizer.onTouch(TouchSample(TouchAction.DOWN, listOf(Pointer(0, 100f, 100f)), 0, 0))
        recognizer.onTouch(
            TouchSample(
                TouchAction.POINTER_DOWN,
                listOf(Pointer(0, 100f, 100f), Pointer(1, 200f, 100f)),
                1,
                16
            )
        )
        recognizer.onTouch(
            TouchSample(
                TouchAction.MOVE,
                listOf(Pointer(0, 100f, 60f), Pointer(1, 200f, 60f)),
                NO_POINTER,
                32
            )
        )

        assertEquals(listOf(GestureCommand.ScrollEnd), recognizer.reset())
        assertTrue("reset is idempotent", recognizer.reset().isEmpty())
    }
}
