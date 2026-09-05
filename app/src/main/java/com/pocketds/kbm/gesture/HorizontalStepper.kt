package com.pocketds.kbm.gesture

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Turns a sideways drag on a key into a count of steps.
 *
 * Shared by two gestures that are the same shape underneath: dragging along the
 * spacebar to walk the text cursor, and dragging left off backspace to delete
 * whole words.
 *
 * Both live on keys that already do something, so the two jobs here are telling
 * a drag from a tap, and remembering that it *was* a drag — a key that also
 * fires on release would type a space at the end of moving the cursor.
 */
class HorizontalStepper(
    private val pxPerStep: Float,
    private val slopPx: Float
) {
    private var originX = 0f
    private var emittedTo = 0f
    private var stepping = false

    fun down(x: Float) {
        originX = x
        emittedTo = x
        stepping = false
    }

    /** Whether this became a drag, and so the key must not fire on release. */
    fun didStep() = stepping

    /**
     * @return steps to act on right now, signed — several at once when a quick
     *   flick crosses more than one between touch events, since dropping them
     *   makes the cursor lag behind the finger.
     */
    fun move(x: Float): Int {
        if (!stepping) {
            val fromOrigin = x - originX
            if (abs(fromOrigin) < slopPx) return 0
            stepping = true
            // Only the slop itself is consumed, not the whole movement that
            // crossed it — swallowing all of it meant the drag that opened the
            // gate produced no step, so the cursor sat still through the first
            // part of every swipe. Paid once, too: later steps measure from
            // here, so slowing down does not stall it.
            emittedTo = originX + slopPx * fromOrigin.sign
        }
        val travel = x - emittedTo
        val steps = truncate(travel / pxPerStep).toInt()
        if (steps == 0) return 0
        emittedTo += steps * pxPerStep
        return steps
    }

}
