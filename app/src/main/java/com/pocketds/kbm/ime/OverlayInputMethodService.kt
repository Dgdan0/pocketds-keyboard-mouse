package com.pocketds.kbm.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup

/**
 * Deliberately minimal. Its only purpose is to be the active input method so
 * `currentInputConnection` becomes available whenever a text field is focused;
 * the actual bottom-screen UI lives in BottomPanelService, independent of this
 * service's onCreate/onDestroy churn (which fires on every focus change and
 * would otherwise tear down a persistent panel each time it's touched).
 */
class OverlayInputMethodService : InputMethodService() {

    companion object {
        var instance: OverlayInputMethodService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCreateInputView(): View =
        View(this).apply { layoutParams = ViewGroup.LayoutParams(0, 0) }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }
}
