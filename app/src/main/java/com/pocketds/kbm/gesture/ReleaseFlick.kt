package com.pocketds.kbm.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** The final moving segment of a scroll, dispatched just before the finger lifts. */
data class FlickSegment(val dx: Float, val dy: Float, val durationMs: Long)

/** Pixels per millisecond, at the moment of release. */
data class ScrollVelocity(val vx: Float, val vy: Float)

/**
 * Works out whether a scroll should carry on through its release, and how far.
 *
 * Ending on a stationary segment hands the app a release velocity of zero and
 * its fling never triggers, which is why scrolling halted the instant you
 * lifted. Keeping the finger moving through the release lets the app's own
 * fling take over — better than simulating decay here, since it matches
 * whatever that app already does, overscroll and all.
 *
 * The first version of this was worse than the problem it solved: it threw
 * pages sideways into their own edges and bounced back, and did something
 * different on every flick. Three things were wrong, and all three are guarded
 * now — the velocity is averaged rather than read off one noisy segment, an
 * incidental cross-axis drift is dropped, and the speed is capped well below
 * what the hardware will accept.
 */
object ReleaseFlick {

    /** Below this the finger was easing to a halt, and a halt is what was meant. */
    private const val MIN_SPEED_PX_PER_MS = 0.35f

    /**
     * Under this share of the dominant axis, movement is drift rather than
     * intent. Two fingers are never perfectly straight, and scroll travel is
     * amplified for sensitivity — so is the wander that comes with it.
     */
    private const val MINOR_AXIS_SHARE = 0.4f

    fun segment(
        vx: Float,
        vy: Float,
        flickMs: Long,
        maxSpeedPxPerMs: Float
    ): FlickSegment? {
        var useVx = vx
        var useVy = vy
        // Drop the incidental axis before judging speed, so a sideways wobble
        // cannot make a slow scroll look fast enough to throw.
        if (abs(vx) < abs(vy) * MINOR_AXIS_SHARE) useVx = 0f
        if (abs(vy) < abs(vx) * MINOR_AXIS_SHARE) useVy = 0f

        val speed = hypot(useVx, useVy)
        if (speed < MIN_SPEED_PX_PER_MS) return null
        if (speed > maxSpeedPxPerMs) {
            // Scaled together, so a capped diagonal still travels the way the
            // finger was going.
            val scale = maxSpeedPxPerMs / speed
            useVx *= scale
            useVy *= scale
        }

        return FlickSegment(useVx * flickMs, useVy * flickMs, flickMs)
    }

    /**
     * Keeps the last few dispatched segments, so the release reads an average
     * rather than whichever single frame happened to be last.
     */
    class Tracker(private val window: Int = 3, private val maxAgeMs: Long = 120L) {
        private data class Sample(val vx: Float, val vy: Float, val atMs: Long)

        private val samples = ArrayDeque<Sample>()

        fun add(dx: Float, dy: Float, durationMs: Long, atMs: Long) {
            if (durationMs <= 0L) return
            if (samples.size >= window) samples.removeFirst()
            samples.addLast(Sample(dx / durationMs, dy / durationMs, atMs))
        }

        /** Null when there is nothing recent enough to call a throw. */
        fun velocity(nowMs: Long): ScrollVelocity? {
            val newest = samples.lastOrNull() ?: return null
            // Movement older than this was before the pause, not the release.
            if (nowMs - newest.atMs > maxAgeMs) return null
            return ScrollVelocity(
                vx = samples.sumOf { it.vx.toDouble() }.toFloat() / samples.size,
                vy = samples.sumOf { it.vy.toDouble() }.toFloat() / samples.size
            )
        }

        fun reset() = samples.clear()
    }
}
