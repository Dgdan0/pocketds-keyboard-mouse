package com.pocketds.kbm.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.PocketColors

/**
 * Backspace with the same accelerating hold-to-repeat-delete behavior as Gboard/iOS:
 * one immediate delete on press, then (after a short initial delay) repeats faster
 * and faster, and once it's ramped all the way up switches to deleting in multi-
 * character chunks instead of one at a time.
 */
fun buildRepeatingBackspaceKey(
    context: Context,
    colors: PocketColors,
    weight: Float,
    onDeleteChars: (Int) -> Unit
): Button {
    val handler = Handler(Looper.getMainLooper())
    var repeatCount = 0

    val tick = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > CHUNK_MODE_AFTER_REPEATS) {
                onDeleteChars(CHUNK_SIZE)
            } else {
                onDeleteChars(1)
            }
            handler.postDelayed(this, intervalForRepeat(repeatCount))
        }
    }

    return Button(context).apply {
        text = "⌫"
        layoutParams = KeyStyler.applyKeyMargin(
            context,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        )
        KeyStyler.styleKey(context, this, colors)

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeatCount = 0
                    onDeleteChars(1)
                    handler.postDelayed(tick, INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(tick)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    true
                }
                else -> false
            }
        }
        // performClick() needs a listener registered to satisfy the accessibility/lint
        // contract, even though the real action already happened in ACTION_DOWN above.
        setOnClickListener { }
    }
}

private const val INITIAL_DELAY_MS = 400L
private const val MIN_INTERVAL_MS = 40L
private const val MAX_INTERVAL_MS = 150L
private const val RAMP_STEPS = 15
private const val CHUNK_MODE_AFTER_REPEATS = 20
private const val CHUNK_SIZE = 6

private fun intervalForRepeat(repeatCount: Int): Long {
    val step = repeatCount.coerceAtMost(RAMP_STEPS)
    return MAX_INTERVAL_MS - (MAX_INTERVAL_MS - MIN_INTERVAL_MS) * step / RAMP_STEPS
}
