package com.pocketds.kbm.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

    // Continuous two-finger scroll gesture state. Android's gesture-continuation API
    // requires waiting for each dispatch's completion callback before extending it
    // further, so rapid touch-move events are coalesced into pendingScrollDx/Dy and
    // flushed as soon as the in-flight segment finishes, instead of firing one
    // independent discrete gesture per batch (which felt like separate disconnected
    // flicks rather than one smooth drag).
    private var scrollStrokeX1: GestureDescription.StrokeDescription? = null
    private var scrollStrokeX2: GestureDescription.StrokeDescription? = null
    private var scrollActive = false
    private var scrollDispatchInFlight = false
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
        private const val FADE_DURATION_MS = 150L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

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
     * Simulates a two-finger drag centered on the cursor position — the only scroll
     * mechanism AccessibilityService's gesture API exposes (there's no direct
     * mouse-wheel/AXIS_VSCROLL injection available to accessibility services). This
     * is one continuous held gesture for the whole scroll session (start→move*→end),
     * not a new touch-down per call, so it reads as a smooth drag rather than a
     * string of disconnected flicks. dx/dy are already sign-adjusted by the caller
     * for the user's invert-scroll preference.
     */
    fun scrollBy(dx: Float, dy: Float) {
        if (!scrollActive) startScroll()
        pendingScrollDx += dx
        pendingScrollDy += dy
        if (!scrollDispatchInFlight) dispatchScrollSegment()
    }

    fun endScroll() {
        if (!scrollActive) return
        scrollActive = false
        if (!scrollDispatchInFlight) finishScrollGesture()
    }

    private fun startScroll() {
        scrollActive = true
        pendingScrollDx = 0f
        pendingScrollDy = 0f
        val fingerOffset = 60f
        scrollY = clamp(cursorY, 0f, screenHeight.toFloat())
        scrollX1 = clamp(cursorX - fingerOffset, 0f, screenWidth.toFloat())
        scrollX2 = clamp(cursorX + fingerOffset, 0f, screenWidth.toFloat())
        scrollStrokeX1 = GestureDescription.StrokeDescription(
            Path().apply { moveTo(scrollX1, scrollY) }, 0, SCROLL_SEGMENT_DURATION_MS, true
        )
        scrollStrokeX2 = GestureDescription.StrokeDescription(
            Path().apply { moveTo(scrollX2, scrollY) }, 0, SCROLL_SEGMENT_DURATION_MS, true
        )
    }

    private fun dispatchScrollSegment() {
        val stroke1 = scrollStrokeX1 ?: return
        val stroke2 = scrollStrokeX2 ?: return
        val dx = pendingScrollDx
        val dy = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        val newY = clamp(scrollY + dy, 0f, screenHeight.toFloat())
        val newX1 = clamp(scrollX1 + dx, 0f, screenWidth.toFloat())
        val newX2 = clamp(scrollX2 + dx, 0f, screenWidth.toFloat())
        val path1 = Path().apply { moveTo(scrollX1, scrollY); lineTo(newX1, newY) }
        val path2 = Path().apply { moveTo(scrollX2, scrollY); lineTo(newX2, newY) }
        scrollX1 = newX1
        scrollX2 = newX2
        scrollY = newY

        val nextStroke1 = stroke1.continueStroke(path1, 0, SCROLL_SEGMENT_DURATION_MS, true)
        val nextStroke2 = stroke2.continueStroke(path2, 0, SCROLL_SEGMENT_DURATION_MS, true)
        scrollStrokeX1 = nextStroke1
        scrollStrokeX2 = nextStroke2

        scrollDispatchInFlight = true
        val gesture = GestureDescription.Builder().addStroke(nextStroke1).addStroke(nextStroke2).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                scrollDispatchInFlight = false
                when {
                    pendingScrollDx != 0f || pendingScrollDy != 0f -> dispatchScrollSegment()
                    !scrollActive -> finishScrollGesture()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                scrollDispatchInFlight = false
            }
        }, null)
    }

    private fun finishScrollGesture() {
        val stroke1 = scrollStrokeX1 ?: return
        val stroke2 = scrollStrokeX2 ?: return
        scrollStrokeX1 = null
        scrollStrokeX2 = null
        val end1 = stroke1.continueStroke(Path().apply { moveTo(scrollX1, scrollY) }, 0, 1L, false)
        val end2 = stroke2.continueStroke(Path().apply { moveTo(scrollX2, scrollY) }, 0, 1L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(end1).addStroke(end2).build(), null, null)
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
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
        instance = null
    }
}
