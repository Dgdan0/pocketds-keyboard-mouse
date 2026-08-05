package com.pocketds.kbm.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.OVAL
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

    companion object {
        var instance: CursorAccessibilityService? = null
            private set

        private const val CURSOR_SIZE_DP = 20
        private const val TAP_DURATION_MS = 40L
        // Android has no native right-click gesture; a long-press is the closest
        // system-wide equivalent (it opens context menus the same way).
        private const val LONG_PRESS_DURATION_MS = 500L
        private const val SCROLL_GESTURE_DURATION_MS = 80L
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
        cursorView = View(this).apply {
            background = GradientDrawable().apply {
                shape = OVAL
                setColor(0xCC00E5FF.toInt())
                setStroke((2 * metrics.density).toInt(), 0xFFFFFFFF.toInt())
            }
        }

        cursorParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt() - sizePx / 2
            y = cursorY.toInt() - sizePx / 2
        }

        windowManager.addView(cursorView, cursorParams)
    }

    fun moveCursorBy(dx: Float, dy: Float) {
        cursorX = clamp(cursorX + dx, 0f, screenWidth.toFloat())
        cursorY = clamp(cursorY + dy, 0f, screenHeight.toFloat())
        cursorParams.x = cursorX.toInt() - cursorView.width / 2
        cursorParams.y = cursorY.toInt() - cursorView.height / 2
        windowManager.updateViewLayout(cursorView, cursorParams)
    }

    fun click() = tapAt(cursorX, cursorY, TAP_DURATION_MS)

    fun rightClick() = tapAt(cursorX, cursorY, LONG_PRESS_DURATION_MS)

    /**
     * Simulates a two-finger swipe centered on the cursor position, since that's
     * the only scroll mechanism AccessibilityService's gesture API exposes (there's
     * no direct mouse-wheel/AXIS_VSCROLL injection available to accessibility
     * services). dx/dy are already sign-adjusted by the caller for the user's
     * invert-scroll preference.
     */
    fun scrollBy(dx: Float, dy: Float) {
        val fingerOffset = 60f
        val startY = clamp(cursorY, 0f, screenHeight.toFloat())
        val startX1 = clamp(cursorX - fingerOffset, 0f, screenWidth.toFloat())
        val startX2 = clamp(cursorX + fingerOffset, 0f, screenWidth.toFloat())
        val endY = clamp(startY + dy, 0f, screenHeight.toFloat())
        val endX1 = clamp(startX1 + dx, 0f, screenWidth.toFloat())
        val endX2 = clamp(startX2 + dx, 0f, screenWidth.toFloat())

        val path1 = Path().apply { moveTo(startX1, startY); lineTo(endX1, endY) }
        val path2 = Path().apply { moveTo(startX2, startY); lineTo(endX2, endY) }
        val duration = SCROLL_GESTURE_DURATION_MS
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, duration))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
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
