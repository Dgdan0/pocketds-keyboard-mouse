package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.kbm.layout.InputMode
import com.pocketds.kbm.ui.KeyStyler
import com.pocketds.kbm.ui.Theme

/**
 * The mode strip + swappable panel (Keyboard/Trackpad/Nub/Split/Full KB), plus quick
 * icons for settings and the 1Password launcher. Built against whatever Context it's
 * given so it can be hosted either inline in the IME's own input view, or inside a
 * Presentation targeting a secondary display.
 */
class InputPanelView(
    context: Context,
    private val keyboardListener: FullKeyboardListener,
    private val trackpadListener: TrackpadPanel.Listener,
    private val onSettingsClick: (() -> Unit)? = null,
    private val onOnePasswordClick: (() -> Unit)? = null
) : LinearLayout(context) {

    private val colors = Theme.colors(context)
    private val panelContainer = FrameLayout(context)
    private val tabs = mutableMapOf<InputMode, TextView>()
    private var activeMode = InputMode.KEYBOARD

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        addView(buildModeStrip())
        addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showMode(InputMode.KEYBOARD)
    }

    private fun buildModeStrip(): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(colors.stripBackground)
            val pad = KeyStyler.keyMargin(context)
            setPadding(pad, pad, pad, pad)
        }
        for (mode in InputMode.entries) {
            val tab = TextView(context).apply {
                text = mode.label
                gravity = Gravity.CENTER
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
                layoutParams = KeyStyler.applyKeyMargin(context, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { showMode(mode) }
            }
            tabs[mode] = tab
            strip.addView(tab)
        }
        if (onSettingsClick != null) strip.addView(iconButton("⚙") { onSettingsClick.invoke() })
        if (onOnePasswordClick != null) strip.addView(iconButton("🔑") { onOnePasswordClick.invoke() })
        return strip
    }

    private fun iconButton(symbol: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = symbol
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(colors.mutedText)
        layoutParams = KeyStyler.applyKeyMargin(context, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
        val iconColors = colors
        background = GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(iconColors.keySurface)
        }
        setOnClickListener { onClick() }
    }

    private fun showMode(mode: InputMode) {
        restyleTab(activeMode, selected = false)
        activeMode = mode
        restyleTab(activeMode, selected = true)

        panelContainer.removeAllViews()
        val panel: View = when (mode) {
            InputMode.KEYBOARD -> KeyboardPanel(context, keyboardListener)
            InputMode.TRACKPAD -> TrackpadPanel(context, trackpadListener)
            InputMode.NUB -> NubKeyboardPanel(context, keyboardListener, trackpadListener)
            InputMode.SPLIT -> SplitPanel(context, keyboardListener, trackpadListener)
            InputMode.FULL_KEYBOARD -> FullKeyboardPanel(context, keyboardListener)
        }
        panelContainer.addView(panel)
    }

    private fun restyleTab(mode: InputMode, selected: Boolean) {
        val tab = tabs[mode] ?: return
        val tabColors = colors
        val bg = if (selected) tabColors.accent else tabColors.keySurface
        tab.setTextColor(if (selected) tabColors.accentText else tabColors.mutedText)
        tab.background = GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(bg)
        }
    }
}
