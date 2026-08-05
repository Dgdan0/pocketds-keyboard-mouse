package com.pocketds.kbm.ime

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * A real desktop-style (TKL) layout: number row with symbols, Tab/bracket row,
 * Caps/semicolon row, Shift/punctuation row, Ctrl+Alt+Space row, and a Home/End/
 * PageUp/PageDown/arrow nav cluster — no function-key row, no Win/Fn (no clear
 * Android equivalent for either). A thin quick-actions strip up top covers
 * Cut/Copy/Paste/Undo/Redo/Select All, since those are common enough to deserve
 * one tap instead of arming Ctrl then tapping a letter.
 *
 * Ctrl/Alt are one-shot modifiers (tap the modifier, then the key it applies to)
 * since there's no way to hold two on-screen buttons down at once the way a
 * physical keyboard chord works.
 */
class FullKeyboardPanel(context: Context, private val listener: FullKeyboardListener) : LinearLayout(context) {

    companion object {
        private val SHIFT_MAP = mapOf(
            '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
            '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')',
            '-' to '_', '=' to '+', '[' to '{', ']' to '}', '\\' to '|',
            ';' to ':', '\'' to '"', ',' to '<', '.' to '>', '/' to '?',
            '`' to '~'
        )
    }

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

        addView(buildQuickActionsRow(), thinRowParams())
        addView(buildNumberRow(), rowParams())
        addView(buildRow(prefix = "Tab" to { listener.onKeyEvent(KeyEvent.KEYCODE_TAB, 0) }, letters = "qwertyuiop", symbols = "[]\\"), rowParams())
        addView(buildCapsRow(), rowParams())
        addView(buildShiftRow(), rowParams())
        addView(buildBottomRow(), rowParams())
        addView(buildNavRow(), rowParams())
    }

    private fun rowParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    private fun thinRowParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.6f)

    private fun isUpper() = capsOn xor shiftOnce

    private fun buildQuickActionsRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(quickActionButton("Cut") { listener.onKeyEvent(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON) })
        row.addView(quickActionButton("Copy") { listener.onKeyEvent(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON) })
        row.addView(quickActionButton("Paste") { listener.onKeyEvent(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON) })
        row.addView(quickActionButton("Undo") { listener.onKeyEvent(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON) })
        row.addView(quickActionButton("Redo") { listener.onKeyEvent(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) })
        row.addView(quickActionButton("Select All") { listener.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON) })
        return row
    }

    private fun buildNumberRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(symbolButton('`'))
        for (c in "1234567890") row.addView(symbolButton(c))
        row.addView(symbolButton('-'))
        row.addView(symbolButton('='))
        row.addView(buildRepeatingBackspaceKey(context, colors, weight = 1.5f) { count -> listener.onBackspace(count) })
        return row
    }

    private fun buildRow(prefix: Pair<String, () -> Unit>, letters: String, symbols: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(keyButton(prefix.first, weight = 1.5f) { prefix.second() })
        for (c in letters) {
            val button = keyButton(c.toString()) { onLetterKey(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        for (c in symbols) row.addView(symbolButton(c))
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
            val button = keyButton(c.toString()) { onLetterKey(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(symbolButton(';'))
        row.addView(symbolButton('\''))
        row.addView(keyButton("Enter", weight = 1.5f, accent = true) { listener.onEnter() })
        return row
    }

    private fun buildShiftRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        shiftButton = keyButton("Shift", weight = 1.5f, accent = shiftOnce) { onShiftTap() }
        row.addView(shiftButton)
        for (c in "zxcvbnm") {
            val button = keyButton(c.toString()) { onLetterKey(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(symbolButton(','))
        row.addView(symbolButton('.'))
        row.addView(symbolButton('/'))
        val shiftButton2 = keyButton("Shift", weight = 1.5f) { onShiftTap() }
        row.addView(shiftButton2)
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        ctrlButton = keyButton("Ctrl", weight = 1.3f, accent = ctrlArmed) {
            ctrlArmed = !ctrlArmed
            restyle(ctrlButton, ctrlArmed)
        }
        altButton = keyButton("Alt", weight = 1.3f, accent = altArmed) {
            altArmed = !altArmed
            restyle(altButton, altArmed)
        }
        val space = keyButton("Space", weight = 4f) { listener.onSpace() }
        val altButton2 = keyButton("Alt", weight = 1.3f) {
            altArmed = !altArmed
            restyle(altButton, altArmed)
        }
        val ctrlButton2 = keyButton("Ctrl", weight = 1.3f) {
            ctrlArmed = !ctrlArmed
            restyle(ctrlButton, ctrlArmed)
        }
        row.addView(ctrlButton)
        row.addView(altButton)
        row.addView(space)
        row.addView(altButton2)
        row.addView(ctrlButton2)
        return row
    }

    private fun buildNavRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(keyButton("Home") { listener.onKeyEvent(KeyEvent.KEYCODE_MOVE_HOME, 0) })
        row.addView(keyButton("PgUp") { listener.onKeyEvent(KeyEvent.KEYCODE_PAGE_UP, 0) })
        row.addView(keyButton("←") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, 0) })
        row.addView(keyButton("↑") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_UP, 0) })
        row.addView(keyButton("↓") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, 0) })
        row.addView(keyButton("→") { listener.onKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, 0) })
        row.addView(keyButton("PgDn") { listener.onKeyEvent(KeyEvent.KEYCODE_PAGE_DOWN, 0) })
        row.addView(keyButton("End") { listener.onKeyEvent(KeyEvent.KEYCODE_MOVE_END, 0) })
        return row
    }

    private fun onLetterKey(c: Char) {
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
            val upper = isUpper()
            listener.onChar(if (upper) c.uppercaseChar().toString() else c.toString())
            if (shiftOnce) {
                shiftOnce = false
                restyle(shiftButton, false)
                applyCase()
            }
        }
    }

    private fun onShiftTap() {
        shiftOnce = !shiftOnce
        restyle(shiftButton, shiftOnce)
        applyCase()
    }

    private fun applyCase() {
        val upper = isUpper()
        letterButtons.forEach { it.text = if (upper) it.text.toString().uppercase() else it.text.toString().lowercase() }
    }

    private fun restyle(button: Button, accent: Boolean) = KeyStyler.styleKey(context, button, colors, accent)

    private fun keyCodeForChar(c: Char): Int = when {
        c.isDigit() -> KeyEvent.KEYCODE_0 + (c - '0')
        c.isLetter() -> KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    /** A symbol/number key whose typed character depends on the current shift
     * state (e.g. "1" normally, "!" while shifted) — matching a real keyboard,
     * rather than needing a separate long-press layer. */
    private fun symbolButton(base: Char): Button = keyButton(base.toString()) {
        // Only one-shot Shift affects symbols/numbers — unlike letters, Caps Lock on
        // a real keyboard never turns "1" into "!" or "." into ">".
        val toType = if (shiftOnce) SHIFT_MAP[base] ?: base else base
        listener.onChar(toType.toString())
        if (shiftOnce) {
            shiftOnce = false
            restyle(shiftButton, false)
        }
    }

    private fun quickActionButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 11f
        setOnClickListener { onClick() }
        layoutParams = KeyStyler.applyKeyMargin(
            context,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        KeyStyler.styleKey(context, this, colors)
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
