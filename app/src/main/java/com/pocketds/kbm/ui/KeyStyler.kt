package com.pocketds.kbm.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.LinearLayout

/** Consistent rounded key-cap look (+ ripple, since default Button chrome doesn't fit the theme). */
object KeyStyler {

    fun styleKey(context: Context, button: Button, colors: PocketColors, accent: Boolean = false) {
        val radius = 10f * context.resources.displayMetrics.density
        val base = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (accent) colors.accent else colors.keySurface)
        }
        button.background = RippleDrawable(ColorStateList.valueOf(colors.keySurfaceRipple), base, null)
        button.setTextColor(if (accent) colors.accentText else colors.keyText)
        button.stateListAnimator = null
        button.isAllCaps = false
        button.setPadding(0, 0, 0, 0)
        button.minimumHeight = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minWidth = 0
    }

    /** Margin every key row should apply between its buttons, in a LinearLayout.LayoutParams. */
    fun keyMargin(context: Context): Int = (3f * context.resources.displayMetrics.density).toInt()

    fun applyKeyMargin(context: Context, params: LinearLayout.LayoutParams): LinearLayout.LayoutParams {
        val m = keyMargin(context)
        params.setMargins(m, m, m, m)
        return params
    }
}
