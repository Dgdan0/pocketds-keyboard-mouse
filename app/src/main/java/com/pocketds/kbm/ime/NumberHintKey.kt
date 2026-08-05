package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.PocketColors

/**
 * A top-row letter key that shows its long-press number faintly in the corner —
 * long-press inserts the number instead of the letter, same as a real keyboard's
 * Fn/number-row-as-secondary-layer convention, just without needing a dedicated row.
 */
fun buildNumberHintKey(
    context: Context,
    colors: PocketColors,
    letter: Char,
    number: Char,
    weight: Float,
    onLetter: () -> Unit,
    onNumber: () -> Unit
): FrameLayout {
    val density = context.resources.displayMetrics.density
    val container = FrameLayout(context).apply {
        layoutParams = KeyStyler.applyKeyMargin(
            context,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, weight)
        )
    }
    val button = Button(context).apply {
        text = letter.toString()
        setOnClickListener { onLetter() }
        setOnLongClickListener { onNumber(); true }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        KeyStyler.styleKey(context, this, colors)
    }
    val hint = TextView(context).apply {
        text = number.toString()
        textSize = 9f
        setTextColor(Color.argb(140, Color.red(colors.mutedText), Color.green(colors.mutedText), Color.blue(colors.mutedText)))
        isClickable = false
        isFocusable = false
        val pad = (3 * density).toInt()
        setPadding(0, pad, pad, 0)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
    }
    container.addView(button)
    container.addView(hint)
    return container
}
