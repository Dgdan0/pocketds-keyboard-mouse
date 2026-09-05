package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.kbm.debug.DebugLog
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
    private val onOnePasswordClick: (() -> Unit)? = null,
    private val onModeChanged: ((InputMode) -> Unit)? = null,
    private val onHideClick: (() -> Unit)? = null,
    private val onCollapseForPicker: (() -> Unit)? = null,
    private val onToggleCompact: (() -> Unit)? = null
) : LinearLayout(context) {

    private val colors = Theme.colors(context)
    private var compactToggle: TextView? = null
    private var isCompact = false
    private val panelContainer = FrameLayout(context)
    private val autofillRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val autofillStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        visibility = GONE
        addView(autofillRow)
    }
    private val tabs = mutableMapOf<InputMode, TextView>()
    private var activeMode = InputMode.KEYBOARD

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        addView(buildModeStrip())
        // Between the tabs and the keys: close to what you are typing, and it
        // pushes the keyboard down rather than covering it.
        autofillStrip.setBackgroundColor(colors.stripBackground)
        addView(autofillStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        // Ayaneo's own system IME-switcher button lives in a strip tied to the real
        // input method window's bounds/insets — since ours is deliberately 0x0 and
        // untouchable (so it doesn't block the top screen), that whole strip never
        // receives real touches for us, even though it does for a normal-sized IME
        // like Gboard. This is our own guaranteed-to-work equivalent.
        strip.addView(iconButton("⌨") { showInputMethodPicker() })
        // Full screen or half, said out loud rather than inferred. Sharing the
        // screen is only ever worth it when something is behind the keyboard,
        // and only the user knows whether they want to see it.
        compactToggle = iconButton(sizeToggleLabel()) { onToggleCompact?.invoke() }
        strip.addView(compactToggle)
        if (onHideClick != null) strip.addView(iconButton("⌄") { onHideClick.invoke() })
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

    private fun showInputMethodPicker() {
        // The system picker dialog renders behind our own Presentation window on
        // this display (ours sits at a higher layer), so it'd open invisibly
        // underneath us — collapse out of the way first so it's actually visible.
        // Deliberately not the same path as the ⌄ hide button: this is a
        // get-out-of-the-way collapse, and shouldn't leave the panel suppressed
        // for the rest of the focus session.
        onCollapseForPicker?.invoke()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun showMode(mode: InputMode) {
        DebugLog.log("mode", "showing $mode")
        restyleTab(activeMode, selected = false)
        activeMode = mode
        restyleTab(activeMode, selected = true)
        onModeChanged?.invoke(mode)

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
        // Same text color whether selected or not — only the background pill marks
        // the active tab. Text color used to dim unselected tabs, which read as
        // "grayed out until chosen" rather than a deliberate selection indicator.
        tab.setTextColor(colors.keyText)
        val bg = if (selected) colors.accent else colors.keySurface
        tab.background = GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(bg)
        }
    }

    // --- autofill suggestions ---------------------------------------------

    /**
     * Makes room for [count] suggestions, before any of them has been drawn.
     *
     * Each arrives on its own callback and they need not arrive in order, so
     * every one gets its slot up front and drops into it — otherwise the
     * best match could end up last.
     */
    fun prepareAutofillSlots(count: Int, chipWidthPx: Int, chipHeightPx: Int, spacingPx: Int) {
        autofillRow.removeAllViews()
        if (count <= 0) {
            autofillStrip.visibility = GONE
            return
        }
        repeat(count) { index ->
            val slot = FrameLayout(context)
            val params = LinearLayout.LayoutParams(chipWidthPx, chipHeightPx)
            if (index > 0) params.leftMargin = spacingPx
            autofillRow.addView(slot, params)
        }
        autofillStrip.visibility = VISIBLE
        autofillStrip.scrollTo(0, 0)
    }

    fun fillAutofillSlot(index: Int, view: View) {
        val slot = autofillRow.getChildAt(index) as? FrameLayout ?: return
        slot.removeAllViews()
        slot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun clearAutofillSuggestions() {
        autofillRow.removeAllViews()
        autofillStrip.visibility = GONE
    }

    /** Half-height while something else uses the screen, or full height. */
    fun setCompact(compact: Boolean) {
        isCompact = compact
        compactToggle?.text = sizeToggleLabel()
    }

    /**
     * One glyph for both states, deliberately: the arrows that point the way it
     * would go (U+2921/2922) are not in every system font, and a missing glyph
     * renders as a box. The panel's own size already says which state it is in.
     */
    private fun sizeToggleLabel() = "↕"
}
