package com.pocketds.kbm.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.pocketds.kbm.debug.DebugLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CursorAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: View
    private lateinit var cursorParams: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0

    // --- Two-finger scroll ---------------------------------------------------
    //
    // Dispatched as one continuous held gesture — a touch-down, a chain of
    // continueStroke() extensions, then a lift — because discrete
    // drag-and-lift segments dispatch fine but feel visibly segmented (real
    // hardware testing confirmed that). Three things make it fiddly:
    //
    //  * continueStroke() only works on a stroke that has actually been
    //    dispatched AND completed. Continuing one that was never itself
    //    dispatched is silently cancelled — that was the original bug here.
    //  * Only one gesture is in flight at a time, and a segment outlasts the
    //    gap between touch-move events, so dispatches queue up and a new flick
    //    can begin while the previous one is still winding down.
    //    scrollGeneration lets callbacks belonging to an abandoned session be
    //    discarded instead of clobbering the new session's state.
    //  * The synthetic fingers travel across a real screen, so a long swipe
    //    runs out of room. On hitting the edge they're lifted and re-planted
    //    back at the anchor, carrying the leftover movement over, rather than
    //    clamping and silently dropping the rest of the swipe.
    //
    // pumpScroll() is the only place that decides what to dispatch next, so the
    // sequencing lives in one spot instead of being restated in every callback.
    private var scrollGeneration = 0
    private var scrollSessionActive = false
    private var scrollDispatchInFlight = false
    private var scrollFingersDown = false
    private var scrollNeedsReanchor = false
    private var scrollStroke1: GestureDescription.StrokeDescription? = null
    private var scrollStroke2: GestureDescription.StrokeDescription? = null
    private var pendingScrollDx = 0f
    private var pendingScrollDy = 0f
    private var scrollX1 = 0f
    private var scrollX2 = 0f
    private var scrollY = 0f

    companion object {
        var instance: CursorAccessibilityService? = null
            private set

        private const val CURSOR_SIZE_DP = 26
        private const val TAP_DURATION_MS = 40L
        // Android has no native right-click gesture; a long-press is the closest
        // system-wide equivalent (it opens context menus the same way). This needs
        // real margin above ViewConfiguration's ~500ms long-press timeout, or our
        // gesture's "up" can land right at the wire and lose the race, silently
        // eating the long-press instead of triggering it.
        private const val LONG_PRESS_DURATION_MS = 650L
        private const val SCROLL_SEGMENT_DURATION_MS = 40L
        // The initial "fingers touch down" stroke — short, since nothing should
        // visibly happen until the first real movement extends it.
        private const val SCROLL_TOUCH_DOWN_MS = 20L
        // How far inside the screen edges the synthetic fingers stay. Leaves room
        // to notice we've run out of travel and re-plant them mid-swipe.
        private const val SCROLL_EDGE_MARGIN_PX = 90f
        private const val SCROLL_FINGER_OFFSET_PX = 60f
        private const val FADE_DURATION_MS = 150L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLog.log("cursor", "accessibility service connected")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        val sizePx = (CURSOR_SIZE_DP * metrics.density).toInt()
        cursorView = CursorPointerView(this, sizePx, 0xFF2BE0CE.toInt())

        cursorParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // The tip of the arrow (the view's top-left) is the hotspot, so it sits
            // exactly at (cursorX, cursorY) — that's what lets it reach true corners;
            // a centered icon always clips its body before the center hits the edge.
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }

        cursorView.visibility = View.GONE
        windowManager.addView(cursorView, cursorParams)
    }

    /** Only meaningful while a cursor-driving mode (Trackpad/Nub/Split) is active —
     * hidden the rest of the time so it doesn't sit on screen during plain typing.
     * Fades rather than snapping, since an instant appear/disappear read as jarring. */
    fun setCursorVisible(visible: Boolean) {
        if (!::cursorView.isInitialized) return
        cursorView.animate().cancel()
        if (visible) {
            cursorView.alpha = 0f
            cursorView.visibility = View.VISIBLE
            cursorView.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        } else {
            cursorView.animate().alpha(0f).setDuration(FADE_DURATION_MS)
                .withEndAction { cursorView.visibility = View.GONE }
                .start()
        }
    }

    fun moveCursorBy(dx: Float, dy: Float) {
        cursorX = clamp(cursorX + dx, 0f, screenWidth.toFloat())
        cursorY = clamp(cursorY + dy, 0f, screenHeight.toFloat())
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        windowManager.updateViewLayout(cursorView, cursorParams)
    }

    fun click() = tapAt(cursorX, cursorY, TAP_DURATION_MS)

    fun rightClick() = tapAt(cursorX, cursorY, LONG_PRESS_DURATION_MS)

    /**
     * Feeds movement into the two-finger drag centred on the cursor — the only
     * scroll mechanism AccessibilityService's gesture API exposes (there's no
     * mouse-wheel/AXIS_VSCROLL injection available to accessibility services).
     * dx/dy are already sign-adjusted by the caller for the invert-scroll setting.
     */
    fun scrollBy(dx: Float, dy: Float) {
        if (!scrollSessionActive) beginScrollSession()
        pendingScrollDx += dx
        pendingScrollDy += dy
        pumpScroll()
    }

    /** The real fingers left the trackpad: lift the synthetic ones, so the next
     * scrollBy() starts a fresh drag anchored back on the cursor. */
    fun endScroll() {
        if (!scrollSessionActive) return
        scrollSessionActive = false
        DebugLog.log("scroll", "session end")
        pumpScroll()
    }

    private fun beginScrollSession() {
        // Bump the generation first: anything still in flight from the previous
        // drag belongs to a dead session now, and its callbacks must not touch
        // the state being set up here.
        scrollGeneration++
        scrollSessionActive = true
        scrollFingersDown = false
        scrollNeedsReanchor = false
        scrollStroke1 = null
        scrollStroke2 = null
        // Deltas left over from a previous or cancelled drag would otherwise be
        // replayed into this one as a single amplified jump.
        pendingScrollDx = 0f
        pendingScrollDy = 0f
        anchorScrollFingers()
        DebugLog.log("scroll", "session begin gen=$scrollGeneration")
    }

    private fun anchorScrollFingers() {
        val minX = SCROLL_EDGE_MARGIN_PX
        val maxX = screenWidth - SCROLL_EDGE_MARGIN_PX
        val minY = SCROLL_EDGE_MARGIN_PX
        val maxY = screenHeight - SCROLL_EDGE_MARGIN_PX
        scrollY = clamp(cursorY, minY, maxY)
        scrollX1 = clamp(cursorX - SCROLL_FINGER_OFFSET_PX, minX, maxX)
        scrollX2 = clamp(cursorX + SCROLL_FINGER_OFFSET_PX, minX, maxX)
    }

    /**
     * Decides what the scroll gesture should do next. Safe to call at any time:
     * it's a no-op while a dispatch is in flight, because that dispatch's
     * callback pumps again when it lands.
     */
    private fun pumpScroll() {
        if (scrollDispatchInFlight) return
        if (scrollFingersDown && scrollNeedsReanchor) {
            scrollNeedsReanchor = false
            liftFingers(reanchor = scrollSessionActive)
            return
        }
        if (!scrollFingersDown) {
            if (scrollSessionActive) dispatchTouchDown()
            return
        }
        if (pendingScrollDx != 0f || pendingScrollDy != 0f) {
            dispatchContinuation()
        } else if (!scrollSessionActive) {
            liftFingers(reanchor = false)
        }
    }

    /** The fingers landing, not yet moving. Has to go out and come back before
     * any movement can be sent, since continueStroke() needs a stroke that has
     * actually completed. */
    private fun dispatchTouchDown() {
        val generation = scrollGeneration
        val stroke1 = GestureDescription.StrokeDescription(
            Path().apply { moveTo(scrollX1, scrollY) }, 0, SCROLL_TOUCH_DOWN_MS, true
        )
        val stroke2 = GestureDescription.StrokeDescription(
            Path().apply { moveTo(scrollX2, scrollY) }, 0, SCROLL_TOUCH_DOWN_MS, true
        )

        scrollDispatchInFlight = true
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    scrollStroke1 = stroke1
                    scrollStroke2 = stroke2
                    scrollFingersDown = true
                    pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    DebugLog.log("scroll", "touch-down cancelled")
                    abandonScroll()
                }
            },
            null
        )
        if (!accepted) {
            scrollDispatchInFlight = false
            DebugLog.log("scroll", "touch-down refused by system")
            abandonScroll()
        }
    }

    private fun dispatchContinuation() {
        val stroke1 = scrollStroke1 ?: return
        val stroke2 = scrollStroke2 ?: return
        val generation = scrollGeneration

        val dx = pendingScrollDx
        val dy = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        val minX = SCROLL_EDGE_MARGIN_PX
        val maxX = screenWidth - SCROLL_EDGE_MARGIN_PX
        val minY = SCROLL_EDGE_MARGIN_PX
        val maxY = screenHeight - SCROLL_EDGE_MARGIN_PX

        val startX1 = scrollX1
        val startX2 = scrollX2
        val startY = scrollY
        val endX1 = clamp(startX1 + dx, minX, maxX)
        val endX2 = clamp(startX2 + dx, minX, maxX)
        val endY = clamp(startY + dy, minY, maxY)

        // Whatever the clamp ate is travel this gesture can't deliver: hand it
        // back to pending and re-plant the fingers, so a long swipe carries on
        // scrolling instead of quietly dying at the edge of the screen.
        val unusedDx = dx - (endX1 - startX1)
        val unusedDy = dy - (endY - startY)
        if (abs(unusedDx) > 0.5f || abs(unusedDy) > 0.5f) {
            pendingScrollDx += unusedDx
            pendingScrollDy += unusedDy
            scrollNeedsReanchor = true
            DebugLog.log("scroll", "out of travel, re-planting fingers")
        }

        if (endX1 == startX1 && endX2 == startX2 && endY == startY) {
            // Nothing left to travel — skip the empty segment (a zero-length
            // stroke risks being refused outright) and go re-plant.
            scrollNeedsReanchor = true
            pumpScroll()
            return
        }

        scrollX1 = endX1
        scrollX2 = endX2
        scrollY = endY

        val nextStroke1 = stroke1.continueStroke(
            Path().apply { moveTo(startX1, startY); lineTo(endX1, endY) },
            0, SCROLL_SEGMENT_DURATION_MS, true
        )
        val nextStroke2 = stroke2.continueStroke(
            Path().apply { moveTo(startX2, startY); lineTo(endX2, endY) },
            0, SCROLL_SEGMENT_DURATION_MS, true
        )

        scrollDispatchInFlight = true
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(nextStroke1).addStroke(nextStroke2).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    scrollStroke1 = nextStroke1
                    scrollStroke2 = nextStroke2
                    pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    DebugLog.log("scroll", "continuation cancelled")
                    abandonScroll()
                }
            },
            null
        )
        if (!accepted) {
            scrollDispatchInFlight = false
            DebugLog.log("scroll", "continuation refused by system")
            abandonScroll()
        }
    }

    /** Lifts the synthetic fingers. With [reanchor] set, they're immediately
     * re-planted back at the cursor so a swipe that ran out of screen can keep
     * going; otherwise the drag is simply over. */
    private fun liftFingers(reanchor: Boolean) {
        val stroke1 = scrollStroke1
        val stroke2 = scrollStroke2
        scrollStroke1 = null
        scrollStroke2 = null
        scrollFingersDown = false

        if (stroke1 == null || stroke2 == null) {
            if (reanchor) {
                anchorScrollFingers()
                pumpScroll()
            }
            return
        }

        val generation = scrollGeneration
        val end1 = stroke1.continueStroke(
            Path().apply { moveTo(scrollX1, scrollY) }, 0, 1L, false
        )
        val end2 = stroke2.continueStroke(
            Path().apply { moveTo(scrollX2, scrollY) }, 0, 1L, false
        )
        if (reanchor) anchorScrollFingers()

        scrollDispatchInFlight = true
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(end1).addStroke(end2).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation == scrollGeneration || scrollSessionActive) pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    pumpScroll()
                }
            },
            null
        )
        if (!accepted) {
            scrollDispatchInFlight = false
            pumpScroll()
        }
    }

    /** Gesture dispatch fell over — drop the whole drag rather than trying to
     * continue from strokes the system has already torn down. */
    private fun abandonScroll() {
        scrollSessionActive = false
        scrollFingersDown = false
        scrollNeedsReanchor = false
        scrollStroke1 = null
        scrollStroke2 = null
        pendingScrollDx = 0f
        pendingScrollDy = 0f
    }

    private fun tapAt(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun clamp(value: Float, minVal: Float, maxVal: Float) = max(minVal, min(maxVal, value))

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log("cursor", "accessibility service destroyed")
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
        instance = null
    }
}
