package com.pocketds.kbm.ime

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * The staggered keyboard with a TrackPoint-style nub spliced between G and H on the
 * home row, matching where a real laptop nub sits. Same long-press-for-numbers and
 * shift one-shot/double-tap-lock behavior as the plain KeyboardPanel.
 */
class NubKeyboardPanel(
    context: Context,
    private val keyboardListener: KeyboardPanel.Listener,
    private val cursorListener: CursorListener
) : LinearLayout(context) {

    companion object {
        private const val DOUBLE_TAP_MS = 350L
        private val TOP_ROW = listOf(
            'q' to '1', 'w' to '2', 'e' to '3', 'r' to '4', 't' to '5',
            'y' to '6', 'u' to '7', 'i' to '8', 'o' to '9', 'p' to '0'
        )
    }

    private val letterButtons = mutableListOf<Button>()
    private val colors = Theme.colors(context)

    private var capsLocked = false
    private var shiftOnce = false
    private var lastShiftTapTime = 0L
    private lateinit var shiftButton: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)

        addView(buildTopRow(), rowParams())
        addView(buildHomeRowWithNub(), rowParams())
        addView(buildStaggeredRow("zxcvbnm", indentWeight = 0.8f), rowParams())
        addView(buildControlRow(), rowParams())
    }

    private fun rowParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun isUpper() = capsLocked || shiftOnce

    private fun buildTopRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for ((letter, number) in TOP_ROW) {
            val key = buildNumberHintKey(
                context, colors, letter, number, weight = 1f,
                onLetter = { onLetterTap(letter) },
                onNumber = { keyboardListener.onChar(number.toString()) }
            )
            letterButtons.add(key.getChildAt(0) as Button)
            row.addView(key)
        }
        return row
    }

    private fun buildStaggeredRow(chars: String, indentWeight: Float): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, indentWeight))
        for (c in chars) {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildHomeRowWithNub(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.3f))
        for (c in "asdfg") {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(NubPanel(context, cursorListener), LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        for (c in "hjkl") {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun onLetterTap(c: Char) {
        keyboardListener.onChar(if (isUpper()) c.uppercaseChar().toString() else c.toString())
        if (shiftOnce && !capsLocked) {
            shiftOnce = false
            applyCase()
        }
    }

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        val isDoubleTap = now - lastShiftTapTime < DOUBLE_TAP_MS
        lastShiftTapTime = now
        when {
            capsLocked -> capsLocked = false
            isDoubleTap -> {
                capsLocked = true
                shiftOnce = false
            }
            else -> shiftOnce = !shiftOnce
        }
        applyCase()
        KeyStyler.styleKey(context, shiftButton, colors, accent = isUpper())
    }

    private fun applyCase() {
        val upper = isUpper()
        letterButtons.forEach { it.text = if (upper) it.text.toString().uppercase() else it.text.toString().lowercase() }
    }

    private fun buildControlRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        shiftButton = keyButton("Shift", weight = 1.3f) { onShiftTap() }
        val space = keyButton("Space", weight = 2.4f) { keyboardListener.onSpace() }
        val backspace = keyButton("⌫", weight = 1f) { keyboardListener.onBackspace() }
        val enter = keyButton("Enter", weight = 1.3f, accent = true) { keyboardListener.onEnter() }
        row.addView(shiftButton)
        row.addView(space)
        row.addView(backspace)
        row.addView(enter)
        return row
    }

    private fun keyButton(
        label: String,
        weight: Float = 1f,
        accent: Boolean = false,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = KeyStyler.applyKeyMargin(
            context,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        )
        KeyStyler.styleKey(context, this, colors, accent)
    }
}
