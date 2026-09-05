package com.pocketds.kbm.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a sideways drag on a key into a count of steps.
 *
 * Shared by two gestures that are the same shape underneath: dragging along the
 * spacebar to walk the text cursor, and dragging left off backspace to delete
 * whole words.
 *
 * The awkward part is that both live on keys that already do something. A drag
 * has to be told from a tap, and once it is a drag the key must not also fire
 * on release — nobody wants a space typed at the end of moving the cursor.
 */
class HorizontalStepperTest {

    private fun stepper() = HorizontalStepper(pxPerStep = 20f, slopPx = 12f)

    @Test
    fun `a still finger is not a drag`() {
        val s = stepper()
        s.down(100f)

        assertEquals(0, s.move(100f))
        assertFalse("a tap must still type its key", s.didStep())
    }

    @Test
    fun `a wobble inside the slop is not a drag`() {
        // Fingers are never still. Anything inside the slop is a tap.
        val s = stepper()
        s.down(100f)

        assertEquals(0, s.move(108f))
        assertFalse(s.didStep())
    }

    @Test
    fun `dragging past the slop starts stepping`() {
        val s = stepper()
        s.down(100f)

        assertEquals(1, s.move(132f))
        assertTrue("the key must not also fire on release", s.didStep())
    }

    @Test
    fun `each further step of travel is another step`() {
        val s = stepper()
        s.down(100f)
        s.move(132f)

        assertEquals(1, s.move(152f))
        assertEquals(1, s.move(172f))
    }

    @Test
    fun `a fast drag reports every step it passed`() {
        // Touch events arrive every ~16ms; a quick flick can cross several
        // steps between two of them, and dropping them makes the cursor lag
        // behind the finger.
        val s = stepper()
        s.down(100f)

        assertEquals(5, s.move(212f))
    }

    @Test
    fun `dragging the other way steps the other way`() {
        val s = stepper()
        s.down(100f)

        assertEquals(-1, s.move(68f))
    }

    @Test
    fun `turning back mid-drag steps back`() {
        val s = stepper()
        s.down(100f)
        s.move(152f)

        assertEquals(-1, s.move(132f))
    }

    @Test
    fun `the slop is only paid once`() {
        // Having crossed it, small movements keep counting — otherwise the
        // cursor would stall every time the finger slowed down.
        val s = stepper()
        s.down(100f)
        s.move(132f)

        assertEquals(0, s.move(140f))
        assertEquals(1, s.move(152f))
    }

    @Test
    fun `a new press starts over`() {
        val s = stepper()
        s.down(100f)
        s.move(200f)
        s.down(100f)

        assertFalse(s.didStep())
        assertEquals(0, s.move(105f))
    }
}
