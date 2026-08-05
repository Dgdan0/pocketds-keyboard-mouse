package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme
import kotlin.math.abs

/**
 * Drag-to-move touchpad with tap-to-click, explicit left/right click buttons, and
 * two-finger drag to scroll. Shared by the Trackpad mode and the top-half of Split.
 */
class TrackpadPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener : CursorListener {
        fun onScroll(dx: Float, dy: Float)
        fun onScrollEnd()
    }

    companion object {
        private const val SENSITIVITY = 1.5f
        // Drags under this distance register as a tap-to-click instead of a move.
        private const val TAP_SLOP_PX = 12f
    }

    init {
        val colors = Theme.colors(context)
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        val margin = KeyStyler.keyMargin(context)
        setPadding(margin, margin, margin, margin)

        val pad = TextView(context).apply {
            text = "Drag to move · Tap to click · Two fingers to scroll"
            gravity = android.view.Gravity.CENTER
            setTextColor(colors.mutedText)
            background = GradientDrawable().apply {
                cornerRadius = 14f * resources.displayMetrics.density
                setColor(colors.keySurface)
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = margin
            }
        }

        var lastX = 0f
        var lastY = 0f
        var totalMoved = 0f
        var isScrolling = false
        var lastAvgX = 0f
        var lastAvgY = 0f

        fun averageX(event: MotionEvent) = (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount
        fun averageY(event: MotionEvent) = (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount

        pad.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    totalMoved = 0f
                    isScrolling = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isScrolling = true
                    lastAvgX = averageX(event)
                    lastAvgY = averageY(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScrolling && event.pointerCount >= 2) {
                        val avgX = averageX(event)
                        val avgY = averageY(event)
                        // Forwarded immediately (not batched) — the service coalesces
                        // these into one continuous held gesture, so there's no need
                        // to hold deltas back here to limit dispatch frequency.
                        listener.onScroll(avgX - lastAvgX, avgY - lastAvgY)
                        lastAvgX = avgX
                        lastAvgY = avgY
                    } else if (!isScrolling) {
                        val dx = (event.x - lastX) * SENSITIVITY
                        val dy = (event.y - lastY) * SENSITIVITY
                        totalMoved += abs(event.x - lastX) + abs(event.y - lastY)
                        lastX = event.x
                        lastY = event.y
                        listener.onMove(dx, dy)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // One finger lifted but at least one remains: fall back to single-finger
                    // move using whichever pointer survives, without counting the multi-touch
                    // gesture so far as tap-to-click movement.
                    if (event.pointerCount - 1 == 1) {
                        if (isScrolling) listener.onScrollEnd()
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        lastX = event.getX(remainingIndex)
                        lastY = event.getY(remainingIndex)
                        totalMoved = TAP_SLOP_PX
                        isScrolling = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isScrolling) {
                        listener.onScrollEnd()
                    } else if (totalMoved < TAP_SLOP_PX) {
                        listener.onLeftClick()
                    }
                    isScrolling = false
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isScrolling) listener.onScrollEnd()
                    isScrolling = false
                    true
                }
                else -> false
            }
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        val leftClick = Button(context).apply {
            text = "Left Click"
            setOnClickListener { listener.onLeftClick() }
            layoutParams = KeyStyler.applyKeyMargin(
                context,
                LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f)
            )
            KeyStyler.styleKey(context, this, colors)
        }
        val rightClick = Button(context).apply {
            text = "Right Click"
            setOnClickListener { listener.onRightClick() }
            layoutParams = KeyStyler.applyKeyMargin(
                context,
                LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f)
            )
            KeyStyler.styleKey(context, this, colors)
        }
        buttonRow.addView(leftClick)
        buttonRow.addView(rightClick)

        addView(pad)
        addView(buttonRow)
    }
}
