package com.pocketds.kbm.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.gesture.HorizontalStepper
import com.pocketds.kbm.settings.HapticSettings
import com.pocketds.kbm.ui.KeyHaptics
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
    onDeleteWord: () -> Unit = {},
    // Last so that call sites can keep passing it as a trailing lambda.
    onDeleteChars: (Int) -> Unit
): Button {
    val handler = Handler(Looper.getMainLooper())
    var repeatCount = 0
    // This key installs its own touch listener below, which replaces the one
    // KeyStyler attaches — so the press feedback has to be done by hand here,
    // and gated, since a held backspace repeats up to 25 times a second and
    // buzzing on each is a rattle rather than feedback.
    val hapticGate = KeyHaptics.RepeatGate()
    val density = context.resources.displayMetrics.density
    val wordSwipe = HorizontalStepper(
        pxPerStep = WORD_SWIPE_STEP_DP * density,
        slopPx = WORD_SWIPE_SLOP_DP * density
    )
    lateinit var key: Button

    val tick = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > CHUNK_MODE_AFTER_REPEATS) {
                onDeleteChars(CHUNK_SIZE)
            } else {
                onDeleteChars(1)
            }
            if (hapticGate.allow(SystemClock.uptimeMillis())) {
                KeyHaptics.perform(key, HapticSettings.strength(key.context))
            }
            handler.postDelayed(this, intervalForRepeat(repeatCount))
        }
    }

    key = Button(context)
    return key.apply {
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
                    wordSwipe.down(event.rawX)
                    hapticGate.release()
                    if (hapticGate.allow(SystemClock.uptimeMillis())) {
                        KeyHaptics.perform(this, HapticSettings.strength(context))
                    }
                    onDeleteChars(1)
                    handler.postDelayed(tick, INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Dragging left off backspace deletes whole words. Any drag
                    // also stops the character repeat: once it is a swipe the
                    // finger is not holding the key down any more, and leaving
                    // the repeat running would eat the line behind the words.
                    val steps = wordSwipe.move(event.rawX)
                    if (wordSwipe.didStep()) handler.removeCallbacks(tick)
                    repeat(-steps.coerceAtMost(0)) { onDeleteWord() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(tick)
                    hapticGate.release()
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

/** Far enough that a wobble while holding to repeat cannot turn into a word
 * delete, since the two gestures live on the same key. */
private const val WORD_SWIPE_SLOP_DP = 24f
private const val WORD_SWIPE_STEP_DP = 34f

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
