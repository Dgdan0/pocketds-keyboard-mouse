package com.pocketds.kbm.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** The final moving segment of a scroll, dispatched just before the finger lifts. */
data class FlickSegment(val dx: Float, val dy: Float, val durationMs: Long)

/**
 * Works out whether a scroll should carry on through its release, and how far.
 *
 * The dispatched scroll used to end with a stationary segment — the finger
 * stopped dead, then let go. Apps read the release velocity to decide whether
 * to fling, and a stopped finger has none, which is why scrolling halted the
 * instant you lifted instead of gliding on.
 *
 * Keeping the finger moving through the release hands the app a real velocity
 * and lets its own fling take over, which is far better than simulating decay
 * ourselves: it matches whatever that app already does, overscroll and all.
 */
object ReleaseFlick {

    /** Below this the finger was easing to a halt, and a halt is what was meant. */
    private const val MIN_SPEED_PX_PER_MS = 0.15f

    /** Movement older than this was not the release, it was before the pause. */
    private const val MAX_AGE_MS = 120L

    fun segment(
        lastDx: Float,
        lastDy: Float,
        segmentMs: Long,
        ageMs: Long,
        flickMs: Long,
        maxPx: Float
    ): FlickSegment? {
        if (segmentMs <= 0L || ageMs > MAX_AGE_MS) return null

        val vx = lastDx / segmentMs
        val vy = lastDy / segmentMs
        if (hypot(vx, vy) < MIN_SPEED_PX_PER_MS) return null

        var dx = vx * flickMs
        var dy = vy * flickMs
        // Scaled together rather than clamped per axis, so a capped diagonal
        // still travels the way the finger was going.
        val distance = hypot(dx, dy)
        if (distance > maxPx) {
            val scale = maxPx / distance
            dx *= scale
            dy *= scale
        }
        if (abs(dx) < 0.5f && abs(dy) < 0.5f) return null

        return FlickSegment(dx, dy, flickMs)
    }
}
