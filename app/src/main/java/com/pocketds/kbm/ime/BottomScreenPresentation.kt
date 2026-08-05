package com.pocketds.kbm.ime

import android.app.Presentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.pocketds.kbm.layout.InputMode

/**
 * Renders the input panel directly on the PocketDS's secondary (bottom) display via
 * the public Presentation API, instead of relying on the system's default IME
 * placement — which puts the keyboard on whichever screen the focused field is on,
 * not necessarily the bottom one.
 */
class BottomScreenPresentation(
    context: Context,
    display: Display,
    private val keyboardListener: FullKeyboardListener,
    private val trackpadListener: TrackpadPanel.Listener,
    private val onSettingsClick: (() -> Unit)? = null,
    private val onOnePasswordClick: (() -> Unit)? = null,
    private val onModeChanged: ((InputMode) -> Unit)? = null
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same flag the real system keyboard uses: touchable, but never takes window
        // focus, so tapping it doesn't end the input session on the focused field's
        // window (on the other display).
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        setContentView(
            InputPanelView(context, keyboardListener, trackpadListener, onSettingsClick, onOnePasswordClick, onModeChanged)
        )
        // getInsetsController()/decorView need the window actually attached, which
        // hasn't happened yet this early in onCreate (Presentation.show() attaches it
        // after onCreate returns) — calling this synchronously here crashes with an NPE
        // deep in PhoneWindow. post() defers it until the view hierarchy is attached.
        window?.decorView?.post { hideSystemBars() }
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
