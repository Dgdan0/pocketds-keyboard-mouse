package com.pocketds.kbm.ime

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * The full keyboard with a TrackPoint-style nub spliced between G and H on the home
 * row, matching where a real laptop nub sits — not a floating overlay across the keys.
 */
class NubKeyboardPanel(
    context: Context,
    private val keyboardListener: KeyboardPanel.Listener,
    cursorListener: CursorListener
) : LinearLayout(context) {

    private var shiftOn = false
    private val letterButtons = mutableListOf<Button>()
    private val colors = Theme.colors(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)

        addView(buildRow("1234567890"), rowParams())
        addView(buildRow("qwertyuiop"), rowParams())
        addView(buildHomeRowWithNub(cursorListener), rowParams())
        addView(buildRow("zxcvbnm"), rowParams())
        addView(buildControlRow(), rowParams())
    }

    private fun rowParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun buildRow(chars: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for (c in chars) {
            val button = keyButton(c.toString()) {
                keyboardListener.onChar(if (shiftOn) c.uppercaseChar().toString() else c.toString())
            }
            if (chars.all { it.isLetter() }) letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildHomeRowWithNub(cursorListener: CursorListener): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for (c in "asdfg") {
            val button = keyButton(c.toString()) {
                keyboardListener.onChar(if (shiftOn) c.uppercaseChar().toString() else c.toString())
            }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(NubPanel(context, cursorListener), LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        for (c in "hjkl") {
            val button = keyButton(c.toString()) {
                keyboardListener.onChar(if (shiftOn) c.uppercaseChar().toString() else c.toString())
            }
            letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildControlRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val shift = keyButton("Shift", weight = 1.3f) {
            shiftOn = !shiftOn
            letterButtons.forEach { it.text = if (shiftOn) it.text.toString().uppercase() else it.text.toString().lowercase() }
        }
        val space = keyButton("Space", weight = 2.4f) { keyboardListener.onSpace() }
        val backspace = keyButton("⌫", weight = 1f) { keyboardListener.onBackspace() }
        val enter = keyButton("Enter", weight = 1.3f, accent = true) { keyboardListener.onEnter() }
        row.addView(shift)
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
