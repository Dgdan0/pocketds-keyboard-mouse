package com.pocketds.kbm.ime

import android.content.Context
import android.widget.LinearLayout

/** Small trackpad on top, full keyboard below — most of the screen stays keyboard. */
class SplitPanel(
    context: Context,
    keyboardListener: KeyboardPanel.Listener,
    trackpadListener: TrackpadPanel.Listener
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        addView(TrackpadPanel(context, trackpadListener), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(KeyboardPanel(context, keyboardListener), LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f))
    }
}
