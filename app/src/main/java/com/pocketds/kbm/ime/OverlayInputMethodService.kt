package com.pocketds.kbm.ime

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import android.inputmethodservice.InputMethodService
import com.pocketds.kbm.debug.DebugLog

/**
 * Deliberately minimal. Its main purpose is to be the active input method so
 * `currentInputConnection` becomes available whenever a text field is focused;
 * the actual bottom-screen UI lives in BottomPanelService, independent of this
 * service's onCreate/onDestroy churn (which fires on every focus change and
 * would otherwise tear down a persistent panel each time it's touched).
 *
 * onStartInputView/onFinishInputView (which only fire while THIS is the
 * selected IME) drive the panel's expand/collapse, so it pops up automatically
 * on field focus and tucks itself back down to a thin handle strip when focus
 * is lost — like a real keyboard, not a separately-managed persistent overlay.
 */
class OverlayInputMethodService : InputMethodService() {

    companion object {
        var instance: OverlayInputMethodService? = null
            private set

        // Lets BottomPanelService know, at the moment it (re)creates the
        // presentation, whether a field is already focused right now — e.g. it
        // was just torn down and recreated by an IME switch while a field kept
        // focus the whole time, so it should come back already expanded.
        var isInputViewActive = false
            private set

        // Some fields/apps fire onFinishInputView followed almost immediately
        // (~20-30ms) by a fresh onStartInputView, even though the field never
        // really lost focus from the user's perspective — a focus-restart quirk
        // seen on real hardware, not something the user did. Collapsing
        // immediately on every onFinishInputView made the panel flicker shut
        // and stay shut on these spurious blur/refocus bursts. Debouncing the
        // collapse and cancelling it if a new onStartInputView arrives in time
        // absorbs that without delaying a real, sustained focus loss noticeably.
        private const val COLLAPSE_DEBOUNCE_MS = 400L
    }

    private val collapseHandler = Handler(Looper.getMainLooper())
    private var pendingCollapse: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        DebugLog.log("ime", "service created")
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

    /**
     * Tells the system this input method occupies no space on the screen the
     * focused app is on.
     *
     * Without this, apps do the normal, correct thing for a normal keyboard:
     * they see a non-zero IME inset and scroll/resize their content upward to
     * keep the focused field visible above it. Our UI is on the *other* display
     * though, so there's nothing to make room for — the field just leaps up the
     * screen for no visible reason (very obvious in apps that keep the field
     * pinned above the keyboard, like a chat composer).
     *
     * Reporting content/visible top insets equal to the window's own height is
     * the documented way to say "my content starts at the very bottom edge",
     * i.e. covers nothing. The empty touchable region matches FLAG_NOT_TOUCHABLE
     * above, so touches keep falling through to the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val windowHeight = window?.window?.decorView?.height ?: 0
        outInsets.contentTopInsets = windowHeight
        outInsets.visibleTopInsets = windowHeight
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isInputViewActive = true
        pendingCollapse?.let {
            collapseHandler.removeCallbacks(it)
            DebugLog.log("ime", "pending collapse cancelled")
        }
        pendingCollapse = null

        DebugLog.log("ime", "input view started (restarting=$restarting)")
        val existing = BottomPanelService.instance
        if (existing != null) {
            // restarting=false means focus genuinely moved to a field, which is a
            // new session: any "user manually hid the panel" suppression from the
            // previous one no longer applies. restarting=true is the same field
            // re-establishing its connection, so the suppression stands.
            existing.expand(freshSession = !restarting)
        } else {
            // Nothing running on the bottom screen yet — most likely the process
            // was started fresh just to service this focus. Bring the panel up so
            // focusing a field never leaves a focused field with no keyboard.
            DebugLog.log("ime", "panel service not running, starting it")
            val intent = Intent(this, BottomPanelService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // Android restricts starting foreground services from the
                // background. An IME servicing a focus is normally exempt, but if
                // it's ever refused, failing silently would look exactly like the
                // panel being broken — so say so in the trace.
                DebugLog.log("ime", "could not start panel service: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        isInputViewActive = false
        DebugLog.log("ime", "input view finished, collapse in ${COLLAPSE_DEBOUNCE_MS}ms")
        val runnable = Runnable {
            pendingCollapse = null
            DebugLog.log("ime", "debounced collapse firing")
            BottomPanelService.instance?.collapse()
        }
        pendingCollapse = runnable
        collapseHandler.postDelayed(runnable, COLLAPSE_DEBOUNCE_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log("ime", "service destroyed")
        pendingCollapse?.let { collapseHandler.removeCallbacks(it) }
        pendingCollapse = null
        if (instance === this) instance = null
    }
}
