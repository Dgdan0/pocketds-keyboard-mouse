package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * Drag-to-move touchpad with tap-to-click, two-finger drag to scroll, and
 * two-finger tap to right-click. Shared by the Trackpad mode and the top of Split.
 *
 * Gesture recognition lives in TrackpadGestureRecognizer via
 * [TrackpadTouchAdapter], not here — this used to hold its own copy of the state
 * machine, and Split's revealed trackpad held another, which is how they came to
 * disagree about whether two-finger scrolling worked.
 */
class TrackpadPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener : CursorListener {
        fun onScroll(dx: Float, dy: Float)
        fun onScrollEnd()
    }

    private val touchAdapter = TrackpadTouchAdapter(listener)

    init {
        val colors = Theme.colors(context)
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        val margin = KeyStyler.keyMargin(context)
        setPadding(margin, margin, margin, margin)

        val pad = TextView(context).apply {
            text = "Drag to move · Tap to click\nTwo fingers to scroll · Two-finger tap to right-click"
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

        pad.setOnTouchListener { v, event ->
            val handled = touchAdapter.onTouchEvent(event)
            // Kept for the accessibility/feedback contract, which setOnTouchListener
            // otherwise bypasses.
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
            handled
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Panels are destroyed on every mode switch, and the whole presentation
        // gets dismissed out from under us when the display is reconfigured.
        touchAdapter.cancel()
    }
}
