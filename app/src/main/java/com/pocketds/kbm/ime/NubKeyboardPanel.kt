package com.pocketds.kbm.ime

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * The iPhone-style keyboard (see KeyboardPanel) with a TrackPoint-style nub spliced
 * between G and H on the home row. The nub only appears on the letters page — the
 * symbols page swaps it out for a plain centered row, same as a real trackpoint
 * keyboard doesn't reposition its nub for a secondary layer.
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
        private const val SYMBOLS_ROW_2 = "-/:;()$&@\""
        private const val SYMBOLS_ROW_3 = ".,?!'"
    }

    private val letterButtons = mutableListOf<Button>()
    private val colors = Theme.colors(context)
    private val pageContainer = FrameLayout(context)

    private var capsLocked = false
    private var shiftOnce = false
    private var lastShiftTapTime = 0L
    private var onSymbolsPage = false
    private lateinit var shiftButton: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        addView(pageContainer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showLettersPage()
    }

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    private fun isUpper() = capsLocked || shiftOnce

    private fun showLettersPage() {
        onSymbolsPage = false
        pageContainer.removeAllViews()
        letterButtons.clear()
        val page = LinearLayout(context).apply { orientation = VERTICAL }
        page.addView(buildTopRow(), rowParams())
        page.addView(buildHomeRowWithNub(), rowParams())
        page.addView(buildLettersBottomRow(), rowParams())
        page.addView(buildControlRow(), rowParams())
        pageContainer.addView(page)
        applyCase()
        KeyStyler.styleKey(context, shiftButton, colors, accent = isUpper())
    }

    private fun showSymbolsPage() {
        onSymbolsPage = true
        pageContainer.removeAllViews()
        val page = LinearLayout(context).apply { orientation = VERTICAL }
        page.addView(buildSymbolsRow("1234567890"), rowParams())
        page.addView(buildSymbolsRow(SYMBOLS_ROW_2), rowParams())
        page.addView(buildSymbolsThirdRow(), rowParams())
        page.addView(buildControlRow(), rowParams())
        pageContainer.addView(page)
    }

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

    private fun buildHomeRowWithNub(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.5f))
        for (c in "asdfg") {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(NubPanel(context, cursorListener), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f))
        for (c in "hjkl") {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.5f))
        return row
    }

    private fun buildLettersBottomRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        shiftButton = keyButton("⇧", weight = 1.5f) { onShiftTap() }
        row.addView(shiftButton)
        for (c in "zxcvbnm") {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        val backspace = buildRepeatingBackspaceKey(context, colors, weight = 1.5f) { count -> keyboardListener.onBackspace(count) }
        row.addView(backspace)
        return row
    }

    private fun buildSymbolsRow(chars: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for (c in chars) {
            row.addView(keyButton(c.toString()) { keyboardListener.onChar(c.toString()) })
        }
        return row
    }

    private fun buildSymbolsThirdRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        for (c in SYMBOLS_ROW_3) {
            row.addView(keyButton(c.toString()) { keyboardListener.onChar(c.toString()) })
        }
        val backspace = buildRepeatingBackspaceKey(context, colors, weight = 1.5f) { count -> keyboardListener.onBackspace(count) }
        row.addView(backspace)
        return row
    }

    private fun buildControlRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val toggleLabel = if (onSymbolsPage) "ABC" else "123"
        val toggle = keyButton(toggleLabel, weight = 1.5f) {
            if (onSymbolsPage) showLettersPage() else showSymbolsPage()
        }
        val space = keyButton("Space", weight = 5.5f) { keyboardListener.onSpace() }
        val enter = keyButton("Enter", weight = 1.7f, accent = true) { keyboardListener.onEnter() }
        row.addView(toggle)
        row.addView(space)
        row.addView(enter)
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
