package com.pocketds.kbm.ime

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * The full desktop-style layout: Esc/Tab/Ctrl/Alt/Caps Lock and arrow keys, on top of
 * the same letter/number rows as the basic KeyboardPanel. Ctrl/Alt are one-shot
 * modifiers (tap the modifier, then the key it applies to) since there's no way to
 * hold two on-screen buttons down at once the way a physical keyboard chord works.
 */
class FullKeyboardPanel(context: Context, private val listener: FullKeyboardListener) : LinearLayout(context) {

    private val letterButtons = mutableListOf<Button>()
    private val colors = Theme.colors(context)

    private var capsOn = false
    private var shiftOnce = false
    private var ctrlArmed = false
    private var altArmed = false

    private lateinit var ctrlButton: Button
    private lateinit var altButton: Button
    private lateinit var capsButton: Button
    private lateinit var shiftButton: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)

        addView(buildRow(prefix = "Esc" to { listener.onKeyEvent(KeyEvent.KEYCODE_ESCAPE, 0) }, chars = "1234567890"), rowParams())
        addView(buildRow(prefix = "Tab" to { listener.onKeyEvent(KeyEvent.KEYCODE_TAB, 0) }, chars = "qwertyuiop"), rowParams())
        addView(buildCapsRow(), rowParams())
        addView(buildShiftRow(), rowParams())
        addView(buildControlRow(), rowParams())
        addView(buildArrowRow(), rowParams())
    }

    private fun rowParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun buildRow(prefix: Pair<String, () -> Unit>, chars: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(keyButton(prefix.first, weight = 1.5f) { prefix.second() })
        for (c in chars) {
            val button = keyButton(c.toString()) { onCharKey(c) }
            if (chars.all { it.isLetter() }) letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildCapsRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        capsButton = keyButton("Caps", weight = 1.5f, accent = capsOn) {
            capsOn = !capsOn
            restyle(capsButton, capsOn)
            applyCase()
        }
        row.addView(capsButton)
        for (c in "asdfghjkl") {
            val button = keyButton(c.toString()) { onCharKey(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildShiftRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        shiftButton = keyButton("Shift", weight = 1.5f, accent = shiftOnce) {
            shiftOnce = !shiftOnce
            restyle(shiftButton, shiftOnce)
            applyCase()
        }
        row.addView(shiftButton)
        for (c in "zxcvbnm") {
            val button = keyButton(c.toString()) { onCharKey(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        return row
    }

    private fun buildControlRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        ctrlButton = keyButton("Ctrl", weight = 1f, accent = ctrlArmed) {
            ctrlArmed = !ctrlArmed
            restyle(ctrlButton, ctrlArmed)
        }
        altButton = keyButton("Alt", weight = 1f, accent = altArmed) {
            altArmed = !altArmed
            restyle(altButton, altArmed)
        }
        val space = keyButton("Space", weight = 2.5f) { listener.onSpace() }
        val backspace = buildRepeatingBackspaceKey(context, colors, weight = 1f) { count -> listener.onBackspace(count) }
        val enter = keyButton("Enter", weight = 1.3f, accent = true) { listener.onEnter() }
        row.addView(ctrlButton)
        row.addView(altButton)
        row.addView(space)
        row.addView(backspace)
        row.addView(enter)
        return row
    }

    private fun buildArrowRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(keyButton("←") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, 0) })
        row.addView(keyButton("↑") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_UP, 0) })
        row.addView(keyButton("↓") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, 0) })
        row.addView(keyButton("→") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, 0) })
        return row
    }

    private fun onCharKey(c: Char) {
        if (ctrlArmed || altArmed) {
            var metaState = 0
            if (ctrlArmed) metaState = metaState or KeyEvent.META_CTRL_ON
            if (altArmed) metaState = metaState or KeyEvent.META_ALT_ON
            listener.onKeyEvent(keyCodeForChar(c), metaState)
            ctrlArmed = false
            altArmed = false
            restyle(ctrlButton, false)
            restyle(altButton, false)
        } else {
            val upper = capsOn xor shiftOnce
            listener.onChar(if (upper) c.uppercaseChar().toString() else c.toString())
            if (shiftOnce) {
                shiftOnce = false
                restyle(shiftButton, false)
                applyCase()
            }
        }
    }

    private fun applyCase() {
        val upper = capsOn xor shiftOnce
        letterButtons.forEach { it.text = if (upper) it.text.toString().uppercase() else it.text.toString().lowercase() }
    }

    private fun restyle(button: Button, accent: Boolean) = KeyStyler.styleKey(context, button, colors, accent)

    private fun keyCodeForChar(c: Char): Int = when {
        c.isDigit() -> KeyEvent.KEYCODE_0 + (c - '0')
        c.isLetter() -> KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
        else -> KeyEvent.KEYCODE_UNKNOWN
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
