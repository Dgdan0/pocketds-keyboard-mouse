package com.pocketds.kbm.ime

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/** A QWERTY panel whose rows stretch to fill the full available height. */
class KeyboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onChar(char: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
    }

    private val rows = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    private var shiftOn = false
    private val letterButtons = mutableListOf<Button>()
    private val colors = Theme.colors(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)

        for (row in rows) {
            addView(buildRow(row), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(buildControlRow(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildRow(chars: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (c in chars) {
            val button = keyButton(c.toString()) {
                listener.onChar(if (shiftOn) c.uppercaseChar().toString() else c.toString())
            }
            if (chars.all { it.isLetter() }) letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildControlRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val shift = keyButton("Shift", weight = 1.3f) {
            shiftOn = !shiftOn
            letterButtons.forEach { it.text = if (shiftOn) it.text.toString().uppercase() else it.text.toString().lowercase() }
        }
        val space = keyButton("Space", weight = 2.4f) { listener.onSpace() }
        val backspace = keyButton("⌫", weight = 1f) { listener.onBackspace() }
        val enter = keyButton("Enter", weight = 1.3f, accent = true) { listener.onEnter() }
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
