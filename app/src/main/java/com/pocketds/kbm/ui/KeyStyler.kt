package com.pocketds.kbm.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.pocketds.kbm.settings.HapticSettings

/** Consistent rounded key-cap look (+ ripple, since default Button chrome doesn't fit the theme). */
object KeyStyler {

    fun styleKey(context: Context, button: Button, colors: PocketColors, accent: Boolean = false) {
        val radius = 10f * context.resources.displayMetrics.density
        val fill = if (accent) colors.accent else colors.keySurface
        val dark = KeyPressTint.isDarkSurface(colors.background)
        val base = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
        }
        val held = GradientDrawable().apply {
            cornerRadius = radius
            setColor(KeyPressTint.pressed(fill, dark))
        }
        // No ripple. It was drawn over the whole key rather than inside its
        // rounded shape, so it read as a wash spreading from the middle instead
        // of anything to do with where the finger was — and being nearly opaque
        // it buried the pressed colour underneath it. It is also an animation,
        // which by its nature trails a finger that has already moved on. The
        // state change is instant, on and off with the key, which is what a
        // good keyboard does.
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), held)
            addState(intArrayOf(), base)
        }
        attachPressHaptic(button)
        button.setTextColor(if (accent) colors.accentText else colors.keyText)
        button.stateListAnimator = null
        button.isAllCaps = false
        button.setPadding(0, 0, 0, 0)
        button.minimumHeight = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minWidth = 0
    }

    /**
     * Buzzes on the way down, not on release — which is when a real key gives
     * way under a finger, and it is what makes typing feel answered rather than
     * merely registered. Keys act on release, so the feedback needs its own
     * hook.
     *
     * Returns false throughout: this only listens, and the button's own click
     * handling has to carry on untouched.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPressHaptic(view: View) {
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pressFeedback(v)
            false
        }
    }

    /**
     * The press tick, for keys that install their own touch handling and so
     * replace the listener [attachPressHaptic] would have set.
     */
    fun pressFeedback(view: View) {
        KeyHaptics.perform(view, HapticSettings.strength(view.context))
    }

    /** Margin every key row should apply between its buttons, in a LinearLayout.LayoutParams. */
    fun keyMargin(context: Context): Int = (3f * context.resources.displayMetrics.density).toInt()

    fun applyKeyMargin(context: Context, params: LinearLayout.LayoutParams): LinearLayout.LayoutParams {
        val m = keyMargin(context)
        params.setMargins(m, m, m, m)
        return params
    }
}
