package com.pocketds.kbm.ime

import android.content.Intent
import android.os.Build
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
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

        /** Chip sizing, in pixels on the bottom screen (1024x768, density 1.6). */
        private const val CHIP_HEIGHT_PX = 72
        private const val CHIP_MIN_WIDTH_PX = 180
        private const val CHIP_SPACING_PX = 12
        private const val MAX_SUGGESTIONS = 6

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

    /**
     * Never show an on-screen keyboard window on the focused app's display.
     *
     * Our keyboard is on the other screen, which makes this the same situation
     * as a hardware keyboard being attached — and this is how an IME says
     * "I'm handling input, but I don't need any screen space for it".
     *
     * That gets rid of the strip along the bottom of the top screen: it was the
     * system's IME navigation bar, which SystemUI draws whenever an input method
     * window is showing, plus the unavoidable minimum-size placeholder window
     * underneath it. Neither is ours to style or remove directly — but with no
     * input view shown, there's nothing for the system to put them around.
     *
     * The input connection is unaffected: it comes from onStartInput, not from
     * having a visible view, so typing still works exactly as before.
     */
    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onCreateInputView(): View {
        // Kept minimal and untouchable for the case where the system decides to
        // show it anyway (onEvaluateInputViewShown is advisory): a zero-size,
        // non-touchable view can't swallow a real touch meant for the app, which
        // used to look like "the top screen stopped responding".
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

    /**
     * Drives the panel instead of onStartInputView, which never fires now that
     * no input view is shown. onStartInput is the session-level callback: it
     * still runs on every focus change, and it's what makes the input
     * connection available.
     *
     * It's also slightly *more* eager than onStartInputView — it fires when a
     * field takes focus even if the app didn't explicitly request a keyboard —
     * which suits this app, where a focused field should mean a usable keyboard.
     */
    /**
     * Tells the framework we can show autofill suggestions, and how big they may
     * be.
     *
     * 1Password is already the autofill service on this device, so the point of
     * all this is that a login field on the top screen offers its credentials
     * as chips on the bottom screen — no juggling 1Password and the keyboard for
     * one screen.
     *
     * Whether this is even called is the open question: the framework hosts
     * inline suggestions in the IME's input view, and we deliberately have none
     * (the panel is a Presentation on the other display). Hence the trace line —
     * it says plainly whether the framework offers us suggestions at all.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        // build() hands back the Bundle the framework passes to the autofill
        // service, which is what actually draws the chip.
        val styles = UiVersions.newStylesBuilder()
            .addStyle(InlineSuggestionUi.newStyleBuilder().build())
            .build()

        // Sized against the bottom screen, which is where the chips appear —
        // not the display this service's own resources describe.
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = bottomScreenWidthPx(),
            chipHeightPx = CHIP_HEIGHT_PX,
            spacingPx = CHIP_SPACING_PX,
            minChipWidthPx = CHIP_MIN_WIDTH_PX,
            maxSuggestions = MAX_SUGGESTIONS
        )

        val spec = InlinePresentationSpec
            .Builder(
                Size(sizing.minWidthPx, sizing.heightPx),
                Size(sizing.maxWidthPx, sizing.heightPx)
            )
            .setStyle(styles)
            .build()

        DebugLog.log("autofill", "asked for suggestions, offering room for ${sizing.count}")
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(sizing.count)
            .build()
    }

    /** Falls back to this display's width if the bottom screen isn't there. */
    private fun bottomScreenWidthPx(): Int {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val bottom = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: return resources.displayMetrics.widthPixels
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        bottom.getMetrics(metrics)
        return metrics.widthPixels
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        DebugLog.log("autofill", "${suggestions.size} suggestion(s) offered")
        return BottomPanelService.instance?.showAutofillSuggestions(suggestions) ?: false
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        isInputViewActive = true
        pendingCollapse?.let {
            collapseHandler.removeCallbacks(it)
            DebugLog.log("ime", "pending collapse cancelled")
        }
        pendingCollapse = null

        DebugLog.log("ime", "input started (restarting=$restarting)")
        val existing = BottomPanelService.instance
        if (existing != null) {
            // restarting=false means focus genuinely moved to a field, which is a
            // new session: any "user manually hid the panel" suppression from the
            // previous one no longer applies. restarting=true is the same field
            // re-establishing its connection, so the suppression stands.
            // The editor's package decides whether a session belongs to an app
            // we handed the bottom screen to, which must not be covered up.
            existing.expand(freshSession = !restarting, editorPackage = info?.packageName)
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

    override fun onFinishInput() {
        super.onFinishInput()
        isInputViewActive = false
        DebugLog.log("ime", "input finished, collapse in ${COLLAPSE_DEBOUNCE_MS}ms")
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
