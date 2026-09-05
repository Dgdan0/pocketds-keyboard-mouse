package com.pocketds.kbm.ime

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.pocketds.kbm.gesture.HorizontalStepper
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * An iPhone-style QWERTY layout: Shift and Backspace share the z-m row (not a
 * separate row below), and a bottom row holds the 123/symbols toggle, Space, and
 * Enter — no dedicated comma/period, matching how iOS keeps those on the symbols
 * page. Long-press a top-row key for its number (shown as a faint corner hint).
 * Shift is one-shot on tap (capitalizes the next letter, then reverts) and locks
 * on double-tap.
 */
class KeyboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onChar(char: String)
        fun onBackspace(count: Int = 1)
        fun onEnter()
        fun onSpace()

        /** Walk the text cursor, from dragging along the spacebar. */
        fun onCursorStep(steps: Int) {}

        /** Delete a whole word, from dragging left off backspace. */
        fun onDeleteWord() {}
    }

    companion object {
        private const val DOUBLE_TAP_MS = 350L
        /** One character per this much travel: close to a finger's own sense of
         * moving through text rather than scrubbing. */
        private const val CURSOR_STEP_DP = 11f
        private const val SWIPE_SLOP_DP = 8f
        private val TOP_ROW = listOf(
            'q' to '1', 'w' to '2', 'e' to '3', 'r' to '4', 't' to '5',
            'y' to '6', 'u' to '7', 'i' to '8', 'o' to '9', 'p' to '0'
        )
        private const val SYMBOLS_ROW_2 = "-/:;()$&@\""
        private const val SYMBOLS_ROW_3 = ".,?!'"
    }

    /** Exposed so containers like SplitHoldTrackpadContainer can exclude this key's
     * area from their own hold-gesture detection (it already has one: repeat-delete).
     * Reassigned whenever the page (letters/symbols) rebuilds, always pointing at
     * whichever backspace key is currently on screen. */
    var backspaceButton: Button? = null
        private set

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
        page.addView(buildCenteredRow("asdfghjkl", sideSpacerWeight = 0.5f), rowParams())
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
                onNumber = { listener.onChar(number.toString()) }
            )
            letterButtons.add(key.getChildAt(0) as Button)
            row.addView(key)
        }
        return row
    }

    private fun buildCenteredRow(chars: String, sideSpacerWeight: Float): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, sideSpacerWeight))
        for (c in chars) {
            val button = keyButton(c.toString()) { onLetterTap(c) }
            letterButtons.add(button)
            row.addView(button)
        }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, sideSpacerWeight))
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
        val backspace = buildRepeatingBackspaceKey(
            context, colors, weight = 1.5f,
            onDeleteWord = { listener.onDeleteWord() }
        ) { count -> listener.onBackspace(count) }
        backspaceButton = backspace
        row.addView(backspace)
        return row
    }

    private fun buildSymbolsRow(chars: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for (c in chars) {
            row.addView(keyButton(c.toString()) { listener.onChar(c.toString()) })
        }
        return row
    }

    private fun buildSymbolsThirdRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        for (c in SYMBOLS_ROW_3) {
            row.addView(keyButton(c.toString()) { listener.onChar(c.toString()) })
        }
        val backspace = buildRepeatingBackspaceKey(
            context, colors, weight = 1.5f,
            onDeleteWord = { listener.onDeleteWord() }
        ) { count -> listener.onBackspace(count) }
        backspaceButton = backspace
        row.addView(backspace)
        return row
    }

    private fun buildControlRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val toggleLabel = if (onSymbolsPage) "ABC" else "123"
        val toggle = keyButton(toggleLabel, weight = 1.5f) {
            if (onSymbolsPage) showLettersPage() else showSymbolsPage()
        }
        val space = keyButton("Space", weight = 5.5f) { listener.onSpace() }
        attachCursorSwipe(space)
        val enter = keyButton("Enter", weight = 1.7f, accent = true) { listener.onEnter() }
        row.addView(toggle)
        row.addView(space)
        row.addView(enter)
        return row
    }

    private fun onLetterTap(c: Char) {
        listener.onChar(if (isUpper()) c.uppercaseChar().toString() else c.toString())
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

    /**
     * Dragging along the spacebar walks the text cursor.
     *
     * Placing a caret in a field on the *other* screen is the fiddliest thing
     * this keyboard asks of you — the default layout has no arrow keys, and the
     * pointer is a whole mode switch away.
     *
     * The drag must cancel the space: nobody means to type one at the end of
     * moving the cursor.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachCursorSwipe(space: Button) {
        val density = resources.displayMetrics.density
        val stepper = HorizontalStepper(
            pxPerStep = CURSOR_STEP_DP * density,
            slopPx = SWIPE_SLOP_DP * density
        )
        space.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stepper.down(event.rawX)
                    KeyStyler.pressFeedback(v)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val steps = stepper.move(event.rawX)
                    if (steps != 0) listener.onCursorStep(steps)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (stepper.didStep()) {
                        // Swallow the click so no space is typed.
                        v.isPressed = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
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
