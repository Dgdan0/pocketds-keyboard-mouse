package com.pocketds.kbm.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

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

    override fun onCreateInputView(): View {
        // Android enforces a minimum IME window size regardless of our requested 0x0
        // layout, so this placeholder still renders as a small touchable patch
        // centered on the focused screen while a field has focus — without this flag
        // it silently swallows any real touch that lands on it (e.g. long-press to
        // select text), which looks like "the screen stopped responding."
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        return View(this).apply { layoutParams = ViewGroup.LayoutParams(0, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }
}
