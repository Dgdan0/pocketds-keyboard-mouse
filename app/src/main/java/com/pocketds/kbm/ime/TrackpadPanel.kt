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
        // The bottom screen (the physical trackpad surface) is smaller than the top
        // screen scrolling actually happens on, so a given finger-travel distance
        // needs to cover more scroll distance than it would 1:1 — otherwise it reads
        // as sluggish compared to scrolling directly on the top screen. Roughly the
        // ratio between the two screens' sizes, on top of the base SENSITIVITY.
        private const val SCROLL_SENSITIVITY = 2.2f
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
        // Once a second finger touches down, we follow ONE finger's raw movement for
        // the scroll delta rather than averaging both — real touch hardware doesn't
        // always report two simultaneous points cleanly, and averaging two noisy
        // signals was less reliable than just tracking one, per a real hardware test.
        var trackedPointerId = -1

        pad.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    totalMoved = 0f
                    isScrolling = false
                    trackedPointerId = event.getPointerId(0)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isScrolling = true
                    val idx = event.findPointerIndex(trackedPointerId)
                    if (idx >= 0) {
                        lastX = event.getX(idx)
                        lastY = event.getY(idx)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScrolling) {
                        val idx = event.findPointerIndex(trackedPointerId)
                        if (idx >= 0) {
                            val x = event.getX(idx)
                            val y = event.getY(idx)
                            // Forwarded immediately (not batched) — the service coalesces
                            // these into one continuous held gesture.
                            listener.onScroll((x - lastX) * SCROLL_SENSITIVITY, (y - lastY) * SCROLL_SENSITIVITY)
                            lastX = x
                            lastY = y
                        }
                    } else {
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
                    // If the finger we were tracking lifted, switch to whichever
                    // remains — but keep treating this as an active scroll right
                    // through the transition. Ending the scroll here (the first of
                    // two fingers lifting, not the last) cut the natural tail of
                    // the swipe motion short: it read as a premature stop, and the
                    // still-moving remaining finger fell through to the cursor-move
                    // branch below instead, showing up as a stray cursor jump right
                    // at the end of the gesture. Only a true final lift (ACTION_UP)
                    // or a cancel should end it.
                    if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        if (remainingIndex < event.pointerCount) {
                            trackedPointerId = event.getPointerId(remainingIndex)
                            lastX = event.getX(remainingIndex)
                            lastY = event.getY(remainingIndex)
                        }
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
