package com.pocketds.kbm.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last moment of a scroll, which decides whether the page coasts.
 *
 * A dispatched scroll ends by lifting the synthetic finger, and that lift used
 * to be a stationary segment: the finger stopped dead, then let go. Apps read
 * the release velocity to decide whether to fling, and a stopped finger has
 * none — so scrolling halted the instant you lifted.
 *
 * Keeping it moving through the release fixes that, but the first attempt was
 * worse than the problem. On the device it threw the page around: sideways as
 * well as down, hard enough to hit the end of the content and spring back, and
 * differently every time. Three faults, all of them tested for here.
 */
class ReleaseFlickTest {

    private val flickMs = 24L
    private val maxSpeed = 3f

    private fun flick(vx: Float, vy: Float) =
        ReleaseFlick.segment(vx, vy, flickMs, maxSpeed)

    // --- it has to happen at all ------------------------------------------

    @Test
    fun `a fast swipe carries on through the lift`() {
        val f = flick(vx = 0f, vy = -2f)

        assertNotNull("a fast release should fling", f)
        assertTrue("and keep going the same way, got ${f!!.dy}", f.dy < 0)
    }

    @Test
    fun `speed is carried over, not invented`() {
        val f = flick(vx = 0f, vy = -2f)!!

        assertEquals(-48f, f.dy, 0.01f)
        assertEquals(24L, f.durationMs)
    }

    @Test
    fun `a slow finish does not fling`() {
        // Easing to a halt and lifting means stop.
        assertNull(flick(vx = 0f, vy = -0.05f))
    }

    @Test
    fun `standing still does not fling`() {
        assertNull(flick(vx = 0f, vy = 0f))
    }

    // --- it must not go sideways ------------------------------------------

    @Test
    fun `a vertical swipe throws nothing sideways`() {
        // The fault that caused the trampolining: a vertical scroll released
        // with a sideways component hits the horizontal end of the content and
        // springs back. Two fingers are never perfectly straight, and the drift
        // is amplified along with everything else.
        val f = flick(vx = 0.6f, vy = -3f)!!

        assertEquals("sideways travel should be dropped", 0f, f.dx, 0.01f)
        assertTrue("while the vertical throw stays", f.dy < 0)
    }

    @Test
    fun `a horizontal swipe throws nothing vertically`() {
        val f = flick(vx = 3f, vy = 0.6f)!!

        assertEquals(0f, f.dy, 0.01f)
        assertTrue(f.dx > 0)
    }

    @Test
    fun `a genuinely diagonal swipe keeps both axes`() {
        // Only the clearly-incidental axis is dropped, not a real diagonal.
        val f = flick(vx = 2f, vy = -2f)!!

        assertTrue("dx kept, got ${f.dx}", f.dx > 0)
        assertTrue("dy kept, got ${f.dy}", f.dy < 0)
    }

    @Test
    fun `dropping the minor axis cannot turn a flick into nothing`() {
        // A mostly-sideways drift that is slow overall should simply not fling,
        // rather than fling along a zeroed axis.
        assertNull(flick(vx = 0.05f, vy = 0.01f))
    }

    // --- it must not throw too hard ---------------------------------------

    @Test
    fun `a very fast swipe is capped`() {
        // 307px in 40ms was reaching the far end of a page in one flick.
        val f = flick(vx = 0f, vy = -40f)!!

        assertTrue("capped, got ${f.dy}", kotlin.math.abs(f.dy) <= maxSpeed * flickMs + 0.01f)
        assertTrue("but still moving", kotlin.math.abs(f.dy) > 0f)
    }

    @Test
    fun `capping a diagonal keeps its direction`() {
        val f = flick(vx = 30f, vy = -30f)!!

        assertEquals(f.dx, -f.dy, 0.01f)
    }

    // --- the velocity it is given has to be steady ------------------------

    @Test
    fun `velocity is averaged over several samples`() {
        // One 40ms segment was the whole measurement, and a single mis-timed
        // frame swung it from 35px to 307px between flicks.
        val t = ReleaseFlick.Tracker()
        t.add(dx = 0f, dy = -40f, durationMs = 40L, atMs = 0L)
        t.add(dx = 0f, dy = -80f, durationMs = 40L, atMs = 40L)
        t.add(dx = 0f, dy = -40f, durationMs = 40L, atMs = 80L)

        // Averaged: (1 + 2 + 1) / 3 px per ms.
        assertEquals(-4f / 3f, t.velocity(nowMs = 100L)!!.vy, 0.01f)
    }

    @Test
    fun `only recent samples count`() {
        val t = ReleaseFlick.Tracker()
        t.add(dx = 0f, dy = -80f, durationMs = 40L, atMs = 0L)

        assertNull("lifting after a pause means stop", t.velocity(nowMs = 2000L))
    }

    @Test
    fun `an empty tracker has no velocity`() {
        assertNull(ReleaseFlick.Tracker().velocity(nowMs = 0L))
    }

    @Test
    fun `a new scroll does not inherit the last one's speed`() {
        val t = ReleaseFlick.Tracker()
        t.add(dx = 0f, dy = -80f, durationMs = 40L, atMs = 0L)
        t.reset()

        assertNull(t.velocity(nowMs = 10L))
    }

    @Test
    fun `a zero-length segment is not a division by zero`() {
        val t = ReleaseFlick.Tracker()
        t.add(dx = 10f, dy = 10f, durationMs = 0L, atMs = 0L)

        assertNull(t.velocity(nowMs = 0L))
    }
}
