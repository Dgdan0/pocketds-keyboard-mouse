package com.pocketds.kbm.ime

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

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
    private val onOnePasswordClick: (() -> Unit)? = null
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same flag the real system keyboard uses: touchable, but never takes window
        // focus, so tapping it doesn't end the input session on the focused field's
        // window (on the other display).
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        setContentView(InputPanelView(context, keyboardListener, trackpadListener, onSettingsClick, onOnePasswordClick))
    }
}
