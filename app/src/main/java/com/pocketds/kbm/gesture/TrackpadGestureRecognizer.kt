package com.pocketds.kbm.gesture

import kotlin.math.hypot
import kotlin.math.max

/**
 * Decides what a sequence of touches on the trackpad means.
 *
 * This used to live twice — once in `TrackpadPanel` as closure variables and
 * again in `SplitHoldTrackpadContainer` as fields, with the constants copied
 * across. They drifted, which is why two-finger scrolling worked on one surface
 * and silently did nothing on the other. Both surfaces now translate
 * `MotionEvent` into [TouchSample] and feed it here, so there is one
 * implementation and no second copy to forget.
 *
 * Commands are **returned** rather than pushed into a listener. That is what
 * lets the tests read as "given these touches, expect these outcomes" with no
 * mocking framework, and it keeps this class free of any Android type.
 *
 * Not thread-safe, and not meant to be: touch delivery is main-thread only.
 */
class TrackpadGestureRecognizer(private val config: GestureConfig = GestureConfig.DEFAULT) {

    private enum class Phase { IDLE, ONE_FINGER, MULTI }

    private var phase = Phase.IDLE
    private var trackedPointerId = NO_POINTER
    private var lastX = 0f
    private var lastY = 0f

    /** Where and when the gesture began, for deciding whether it was a tap. */
    private var downX = 0f
    private var downY = 0f
    private var gestureStartMs = 0L

    /**
     * The furthest the finger ever got from where it landed — not the distance
     * it travelled, and not where it ended up.
     *
     * Accumulated travel (what this used to be) meant slow jitter during a
     * still press added up past the slop and swallowed a legitimate tap. Final
     * displacement would go too far the other way: a 60px excursion that
     * happens to return to the start is not a tap either.
     */
    private var maxExcursionPx = 0f

    /** A two-finger tap is only a right-click if it was *two* fingers. */
    private var maxPointerCount = 0

    /** Sticky for the rest of the gesture: dropping back to one finger must not
     * fall through to moving the cursor. */
    private var scrollLatched = false
    private var didEmitScroll = false

    /** When and where the last tap lifted, so a press that follows it closely
     * can be recognised as the second half of a double tap. */
    private var lastTapUpMs: Long? = null
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f

    /** This press followed a tap closely enough that holding and moving it
     * should drag out a selection rather than push the pointer. */
    private var couldDrag = false
    private var dragging = false

    private var pendingScrollDx = 0f
    private var pendingScrollDy = 0f
    private var scrollGateOpen = false
    private var gateOriginX = 0f
    private var gateOriginY = 0f

    fun onTouch(sample: TouchSample): List<GestureCommand> = when (sample.action) {
        TouchAction.DOWN -> onDown(sample)
        TouchAction.POINTER_DOWN -> onPointerDown(sample)
        TouchAction.MOVE -> onMove(sample)
        TouchAction.POINTER_UP -> onPointerUp(sample)
        TouchAction.UP -> onUp(sample)
        TouchAction.CANCEL -> onCancel()
    }

    /** The surface was torn down mid-gesture. Ends anything in flight — without
     * this, a panel dismissed while scrolling leaves the synthetic finger
     * pressed on the other screen. */
    fun reset(): List<GestureCommand> {
        val commands = when {
            dragging -> listOf(GestureCommand.DragEnd)
            scrollLatched && didEmitScroll -> listOf(GestureCommand.ScrollEnd)
            else -> emptyList()
        }
        clear()
        return commands
    }

    private fun onDown(sample: TouchSample): List<GestureCommand> {
        val pointer = sample.pointer(sample.actionPointerId) ?: sample.pointers.firstOrNull() ?: return emptyList()
        clear()
        phase = Phase.ONE_FINGER
        trackedPointerId = pointer.id
        lastX = pointer.x
        lastY = pointer.y
        downX = pointer.x
        downY = pointer.y
        gestureStartMs = sample.eventTimeMs
        maxPointerCount = sample.pointerCount
        // Close on the heels of a tap, and in the same place: this may be the
        // hold half of double-tap-and-drag. Only "may" — released without
        // moving it is simply the second click of a double click, and that has
        // to keep working.
        val sinceTap = lastTapUpMs?.let { sample.eventTimeMs - it }
        couldDrag = sinceTap != null &&
            sinceTap <= config.doubleTapMs &&
            hypot(pointer.x - lastTapUpX, pointer.y - lastTapUpY) <= config.tapSlopPx
        return emptyList()
    }

    private fun onPointerDown(sample: TouchSample): List<GestureCommand> {
        maxPointerCount = max(maxPointerCount, sample.pointerCount)
        // A thumb resting mid-selection must not turn the drag into a scroll.
        if (dragging) return emptyList()
        // Once a second finger lands this is a scroll gesture, even if a finger
        // lifts again later.
        scrollLatched = true
        phase = Phase.MULTI

        // Re-anchor onto the tracked finger so the first scroll sample measures
        // movement from here rather than reporting the gap as travel.
        val tracked = sample.pointer(trackedPointerId) ?: sample.pointers.firstOrNull()
        if (tracked != null) {
            trackedPointerId = tracked.id
            lastX = tracked.x
            lastY = tracked.y
            openGateAt(tracked.x, tracked.y)
        }
        return emptyList()
    }

    private fun onMove(sample: TouchSample): List<GestureCommand> {
        if (phase == Phase.IDLE) {
            // A move with no down before it. Split's revealed trackpad starts
            // intercepting part-way through a gesture, so this is normal there:
            // anchor quietly and report nothing rather than emitting the
            // position as a jump or mistaking it for a tap.
            val pointer = sample.pointers.firstOrNull() ?: return emptyList()
            phase = Phase.ONE_FINGER
            trackedPointerId = pointer.id
            lastX = pointer.x
            lastY = pointer.y
            downX = pointer.x
            downY = pointer.y
            gestureStartMs = sample.eventTimeMs
            maxPointerCount = max(maxPointerCount, sample.pointerCount)
            return emptyList()
        }

        val tracked = sample.pointer(trackedPointerId) ?: return emptyList()
        val rawDx = tracked.x - lastX
        val rawDy = tracked.y - lastY
        lastX = tracked.x
        lastY = tracked.y
        maxExcursionPx = max(maxExcursionPx, hypot(tracked.x - downX, tracked.y - downY))

        if (!scrollLatched) {
            val move = GestureCommand.MoveCursor(
                dx = rawDx * config.cursorSensitivity,
                dy = rawDy * config.cursorSensitivity
            )
            if (couldDrag && !dragging) {
                dragging = true
                // Before the movement, not after: press first, or the first
                // stretch of the selection is dragged with nothing held down.
                return listOf(GestureCommand.DragStart, move)
            }
            return listOf(move)
        }

        // Accumulate rather than discard, so opening the gate loses no travel.
        pendingScrollDx += rawDx * config.scrollSensitivity
        pendingScrollDy += rawDy * config.scrollSensitivity

        if (!scrollGateOpen) {
            val moved = hypot(tracked.x - gateOriginX, tracked.y - gateOriginY)
            if (moved <= config.scrollStartSlopPx) return emptyList()
            scrollGateOpen = true
        }

        val dx = pendingScrollDx
        val dy = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f
        didEmitScroll = true
        return listOf(GestureCommand.Scroll(dx, dy))
    }

    private fun onPointerUp(sample: TouchSample): List<GestureCommand> {
        // Deliberately never ends the scroll: only a true final lift does. Ending
        // here cut the tail off every swipe, and left the still-moving finger to
        // be reinterpreted as a stray cursor jump.
        if (sample.actionPointerId != trackedPointerId) return emptyList()

        val survivor = sample.pointers.firstOrNull { it.id != sample.actionPointerId }
            ?: return emptyList()
        trackedPointerId = survivor.id
        lastX = survivor.x
        lastY = survivor.y
        // The surviving finger is somewhere else entirely, so the gate's origin
        // has to move with it or the handover distance would fling the gate open
        // by itself. Its open/closed state is left alone: re-arming it made an
        // established scroll stall again part-way through, which felt like the
        // scroll suddenly going heavy.
        gateOriginX = survivor.x
        gateOriginY = survivor.y
        return emptyList()
    }

    private fun onUp(sample: TouchSample): List<GestureCommand> {
        val durationMs = sample.eventTimeMs - gestureStartMs
        val wasBrief = durationMs <= config.tapMaxDurationMs
        val stayedPut = maxExcursionPx <= config.tapSlopPx

        if (dragging) {
            // No click on the way out: it would collapse the selection just made.
            clear()
            return listOf(GestureCommand.DragEnd)
        }
        // Remember a completed tap, so a press that follows it can tell it is
        // the second of a pair.
        if (!scrollLatched && wasBrief && stayedPut) {
            lastTapUpMs = sample.eventTimeMs
            lastTapUpX = lastX
            lastTapUpY = lastY
        }

        val commands = when {
            scrollLatched -> {
                // Only close a scroll that actually started. Ending one that
                // never began made the other screen see a touch-down and lift
                // at the same spot — a stray tap.
                val ending =
                    if (didEmitScroll) listOf(GestureCommand.ScrollEnd) else emptyList()
                // Two fingers down, over quickly, and it stayed put: a
                // two-finger tap, which is the conventional right-click.
                //
                // Deliberately not conditioned on whether a scroll was emitted.
                // The gate is only a few pixels, so an ordinary tap's wobble
                // trips it, and ruling out a right-click on that basis is what
                // stopped two-finger taps working once the gate came down from
                // 8px. Travel is the honest test, and it is checked here
                // against the tap slop — a few pixels moved nothing the user
                // can see, whereas a gesture meant as a scroll goes far past
                // it. Any scroll that did start is closed first, so no finger
                // is left pressed on the other screen.
                if (maxPointerCount == 2 && wasBrief && stayedPut) {
                    ending + GestureCommand.RightClick
                } else {
                    ending
                }
            }
            wasBrief && stayedPut -> listOf(GestureCommand.LeftClick)
            else -> emptyList()
        }
        clear()
        return commands
    }

    private fun onCancel(): List<GestureCommand> {
        // Never turns into a click: a cancelled touch is one the system took
        // away, not one the user completed.
        val commands = when {
            dragging -> listOf(GestureCommand.DragEnd)
            scrollLatched && didEmitScroll -> listOf(GestureCommand.ScrollEnd)
            else -> emptyList()
        }
        clear()
        return commands
    }

    private fun openGateAt(x: Float, y: Float) {
        scrollGateOpen = false
        gateOriginX = x
        gateOriginY = y
        pendingScrollDx = 0f
        pendingScrollDy = 0f
    }

    private fun clear() {
        phase = Phase.IDLE
        trackedPointerId = NO_POINTER
        maxExcursionPx = 0f
        maxPointerCount = 0
        scrollLatched = false
        didEmitScroll = false
        // Deliberately not lastTapUp*: that is what the *next* gesture reads to
        // know it is the second of a pair.
        couldDrag = false
        dragging = false
        scrollGateOpen = false
        pendingScrollDx = 0f
        pendingScrollDy = 0f
    }
}
