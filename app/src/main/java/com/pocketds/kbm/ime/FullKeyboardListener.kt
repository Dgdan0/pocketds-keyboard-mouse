package com.pocketds.kbm.ime

/** Extends the basic keyboard listener with raw key events for non-character keys
 * (Tab, Esc, arrows) and Ctrl/Alt-modified key combos. */
interface FullKeyboardListener : KeyboardPanel.Listener {
    fun onKeyEvent(keyCode: Int, metaState: Int)
}
