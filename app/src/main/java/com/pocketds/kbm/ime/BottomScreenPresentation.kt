package com.pocketds.kbm.ime

import android.app.Presentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.pocketds.kbm.layout.InputMode
import com.pocketds.kbm.ui.Theme

/**
 * Renders the input panel directly on the PocketDS's secondary (bottom) display via
 * the public Presentation API, instead of relying on the system's default IME
 * placement — which puts the keyboard on whichever screen the focused field is on,
 * not necessarily the bottom one.
 *
 * The window resizes between a thin "handle" strip (just a tap target pinned to
 * the top — everything else on this display, e.g. an app launched here, shows
 * through underneath) and the full keyboard/trackpad panel, rather than being torn
 * down and recreated, so toggling is instant. BottomPanelService is what decides
 * *whether this exists at all* (only while PocketDS Keyboard is the selected IME);
 * this class only handles expanded-vs-collapsed within that.
 */
class BottomScreenPresentation(
    context: Context,
    display: Display,
    private val keyboardListener: FullKeyboardListener,
    private val trackpadListener: TrackpadPanel.Listener,
    private val onSettingsClick: (() -> Unit)? = null,
    private val onOnePasswordClick: (() -> Unit)? = null,
    private val onModeChanged: ((InputMode) -> Unit)? = null,
    private val onHideRequested: (() -> Unit)? = null
) : Presentation(context, display) {

    private lateinit var handleBar: TextView
    private lateinit var inputPanelView: InputPanelView
    private var expanded = false

    companion object {
        private const val HANDLE_HEIGHT_DP = 40
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same flag the real system keyboard uses: touchable, but never takes window
        // focus, so tapping it doesn't end the input session on the focused field's
        // window (on the other display).
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val colors = Theme.colors(context)
        handleBar = TextView(context).apply {
            text = "⌄  PocketDS Keyboard — tap to show"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(colors.keyText)
            setBackgroundColor(colors.stripBackground)
            setOnClickListener { setExpanded(true) }
        }
        inputPanelView = InputPanelView(
            context, keyboardListener, trackpadListener,
            onSettingsClick, onOnePasswordClick, onModeChanged,
            onHideClick = {
                setExpanded(false)
                onHideRequested?.invoke()
            }
        )

        val root = FrameLayout(context).apply {
            addView(handleBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(inputPanelView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        // getInsetsController()/decorView need the window actually attached, which
        // hasn't happened yet this early in onCreate (Presentation.show() attaches it
        // after onCreate returns) — calling this synchronously here crashes with an NPE
        // deep in PhoneWindow. post() defers it until the view hierarchy is attached.
        window?.decorView?.post { hideSystemBars() }
        applyExpandedState()
    }

    fun isExpanded() = expanded

    /** Resizes the window itself (not just child visibility) between a thin handle
     * strip and the full display. Collapsed, whatever's underneath on this display
     * (another app launched here, or nothing) shows through everywhere except that
     * thin strip — instead of a fullscreen overlay silently sitting on top of it. */
    fun setExpanded(expand: Boolean) {
        expanded = expand
        applyExpandedState()
    }

    private fun applyExpandedState() {
        if (!::handleBar.isInitialized) return
        handleBar.visibility = if (expanded) View.GONE else View.VISIBLE
        inputPanelView.visibility = if (expanded) View.VISIBLE else View.GONE
        val win = window ?: return
        val handlePx = (HANDLE_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        win.setGravity(Gravity.TOP)
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, if (expanded) WindowManager.LayoutParams.MATCH_PARENT else handlePx)
    }

    /**
     * Best-effort attempt at hiding whatever system bar/strip Android (or Ayaneo's
     * launcher) draws on this display — untested, since it's a guess at what was
     * described without a screenshot to confirm against.
     */
    private fun hideSystemBars() {
        val win = window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false)
            win.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
