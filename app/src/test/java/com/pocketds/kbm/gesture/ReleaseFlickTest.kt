package com.pocketds.kbm.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last moment of a scroll, which decides whether the page coasts.
 *
 * A dispatched scroll ends by lifting the synthetic finger, and that lift was a
 * stationary segment: the finger stopped dead and then let go. Apps read the
 * release velocity to decide whether to fling, and a stopped finger has none —
 * which is why scrolling here halts the instant you lift instead of gliding on.
 *
 * The fix is to keep moving through the release, at the speed the finger was
 * already going. Whether to do that at all is the interesting part: letting go
 * after a pause means stop, not throw.
 */
class ReleaseFlickTest {

    private val flickMs = 40L
    private val maxPx = 400f

    private fun flick(
        dx: Float,
        dy: Float,
        segmentMs: Long = 40L,
        ageMs: Long = 0L
    ) = ReleaseFlick.segment(dx, dy, segmentMs, ageMs, flickMs, maxPx)

    @Test
    fun `a fast swipe carries on through the lift`() {
        val f = flick(dx = 0f, dy = -80f)

        assertNotNull("a fast release should fling", f)
        assertTrue("it should keep going the same way, got ${f!!.dy}", f.dy < 0)
    }

    @Test
    fun `speed is carried over, not invented`() {
        // 80px in 40ms is 2px/ms; over a 40ms release that is another 80px.
        val f = flick(dx = 0f, dy = -80f)!!

        assertEquals(-80f, f.dy, 0.01f)
        assertEquals(40L, f.durationMs)
    }

    @Test
    fun `a slow finish does not fling`() {
        // Easing to a halt and lifting means stop. Flinging there would send
        // the page off when the user was lining something up.
        assertNull(flick(dx = 0f, dy = -2f))
    }

    @Test
    fun `lifting after a pause does not fling`() {
        // The finger was fast, but that was a while ago and it has been resting
        // since. Only the last moment counts.
        assertNull(flick(dx = 0f, dy = -80f, ageMs = 300L))
    }

    @Test
    fun `sideways speed is carried too`() {
        val f = flick(dx = 60f, dy = 0f)!!

        assertTrue("should travel right, got ${f.dx}", f.dx > 0)
        assertEquals(0f, f.dy, 0.01f)
    }

    @Test
    fun `a diagonal keeps its direction`() {
        val f = flick(dx = 40f, dy = -40f)!!

        assertEquals("the two axes should stay in proportion", f.dx, -f.dy, 0.01f)
    }

    @Test
    fun `a very fast swipe is capped`() {
        // The release still has to land on screen, and an unbounded throw would
        // ask for a stroke that leaves it.
        val f = flick(dx = 0f, dy = -4000f)!!

        assertTrue("capped to $maxPx, got ${f.dy}", kotlin.math.abs(f.dy) <= maxPx)
        assertTrue("but still moving", kotlin.math.abs(f.dy) > 0f)
    }

    @Test
    fun `a capped diagonal keeps its direction`() {
        val f = flick(dx = 3000f, dy = -3000f)!!

        assertEquals(f.dx, -f.dy, 0.01f)
    }

    @Test
    fun `a zero-length segment is not a division by zero`() {
        assertNull(flick(dx = 10f, dy = 10f, segmentMs = 0L))
    }

    @Test
    fun `standing still does not fling`() {
        assertNull(flick(dx = 0f, dy = 0f))
    }
}
