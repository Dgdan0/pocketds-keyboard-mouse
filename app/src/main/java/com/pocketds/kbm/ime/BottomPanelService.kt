package com.pocketds.kbm.ime

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import com.pocketds.kbm.MainActivity
import com.pocketds.kbm.accessibility.CursorAccessibilityService
import com.pocketds.kbm.debug.DebugLog
import android.util.DisplayMetrics
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import androidx.annotation.RequiresApi
import com.pocketds.kbm.launch.launchOnePasswordOnDisplay
import com.pocketds.kbm.layout.InputMode
import com.pocketds.kbm.settings.ScrollSettings
import com.pocketds.kbm.text.WordDelete

/**
 * Owns the bottom-screen Presentation for the lifetime of the app, independent of
 * IME focus churn. Keyboard actions forward to whichever field currently has an
 * active InputConnection (via OverlayInputMethodService); trackpad actions forward
 * to CursorAccessibilityService. Both are no-ops if their target isn't active yet,
 * which just means "not focused"/"accessibility not enabled" rather than a crash.
 *
 * The presentation only exists at all while PocketDS Keyboard is the selected
 * system IME (a ContentObserver on DEFAULT_INPUT_METHOD tears it down completely
 * the moment you switch to something else, e.g. Gboard — so nothing is left
 * covering whatever else is on that display). While it exists, it toggles between
 * collapsed (a thin handle strip) and expanded (the full panel): expand() on field
 * focus, collapse() on focus loss, both driven by OverlayInputMethodService.
 */
class BottomPanelService : Service(), FullKeyboardListener, TrackpadPanel.Listener {

    private var bottomPresentation: BottomScreenPresentation? = null
    private var secondaryDisplayId: Int? = null
    private var currentMode: InputMode = InputMode.KEYBOARD
    /** Tracked here rather than read back off the presentation, which is briefly
     * stale while a new one is being constructed. */
    private var panelExpanded = false

    // Set when the user taps the panel's own hide button, so the *current* focus
    // session doesn't immediately auto-expand it again (e.g. on an input restart
    // for the same field). Cleared as soon as focus moves to a new field.
    private var manuallyCollapsedThisSession = false

    // A Presentation dismisses itself if the display it's on is removed or
    // reconfigured, which happens on this device when apps are launched or moved
    // between screens. Telling that apart from our own teardown matters: a
    // system-initiated dismissal leaves us holding a dead presentation, and
    // every later expand() call quietly does nothing on it.
    private var dismissingDeliberately = false
    private var lastAutoRecreateAt = 0L

    companion object {
        var instance: BottomPanelService? = null
            private set

        private const val CHANNEL_ID = "pocketds_bottom_panel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_REFRESH_THEME = "com.pocketds.kbm.ACTION_REFRESH_THEME"

        /** Long enough for a launch to settle, short enough not to be seen. */
        private const val WINDOW_SETTLE_MS = 250L

        /** Enough context to find a word boundary without hauling back a whole
         * document on every swipe. */
        private const val WORD_LOOKBACK_CHARS = 128
    }

    private val defaultImeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshPresentationForCurrentIme()
        }
    }

    /**
     * The bottom display gets destroyed and recreated under us — its id has been
     * seen going 2 -> 4 — when apps are launched or moved between screens.
     *
     * A Presentation is bound to the Display it was built with, so when that
     * happens the old one becomes a zombie: it still reports itself as visible,
     * drawn and top of z-order, while the physical screen shows something else
     * entirely. Every expand() then "succeeds" against a window nobody can see,
     * which is exactly what "the keyboard stopped opening" looked like. The
     * system does sometimes dismiss it for us, but not reliably, so the display
     * lifecycle has to be watched directly.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) return
            DebugLog.log("panel", "display $displayId added")
            rebuildForDisplayChange()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != secondaryDisplayId) return
            DebugLog.log("panel", "our display ($displayId) was removed")
            rebuildForDisplayChange()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != secondaryDisplayId) return
            DebugLog.log("panel", "our display ($displayId) was reconfigured")
            rebuildForDisplayChange()
        }
    }

    /** Rebuilds the presentation against whatever the bottom display is now,
     * rate-limited so a display that keeps churning can't spin us. */
    private fun rebuildForDisplayChange() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoRecreateAt < 700L) return
        lastAutoRecreateAt = now
        val wasExpanded = panelExpanded
        dismissPresentation()
        refreshPresentationForCurrentIme()
        if (wasExpanded) bottomPresentation?.setExpanded(true)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, defaultImeObserver
        )
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshPresentationForCurrentIme()
    }

    /**
     * The app currently using the bottom screen, or null when the panel owns it.
     *
     * Our panel covers that screen completely, so anything running there is
     * behind it. Tracked whoever launched it: reacting only to launches of our
     * own meant an app opened from the launcher got sat on.
     */
    private var bottomScreenOccupant: String? = null

    /**
     * The user tapped the bubble while something else held the bottom screen,
     * which is them asking for the keyboard over it. Holds until that app goes
     * away, so the keyboard then behaves normally rather than needing a tap per
     * field.
     */
    private var userTookScreenBack = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_THEME) {
            val wasExpanded = panelExpanded || OverlayInputMethodService.isInputViewActive
            findSecondaryDisplay()?.let { showOnSecondaryDisplay(it, wasExpanded) }
            // The pointer is themed too (white in light mode, accent in dark).
            CursorAccessibilityService.instance?.refreshTheme()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Expands the panel to its full size — a no-op if there's no presentation
     * right now (we're not the selected IME), or if the user manually hid it
     * during this same focus session.
     *
     * @param freshSession focus has genuinely moved to a field, as opposed to the
     *   same field re-establishing its input connection. A fresh session clears
     *   any manual-hide suppression: hiding the panel applies to the field you
     *   hid it on, not to every field you touch afterwards.
     */
    fun expand() {
        if (bottomPresentation?.isBlackedOut() == true) {
            // The screen was turned off deliberately, to watch something on the
            // other one. Focusing a field is not a reason to light it back up;
            // a touch is, and that is what brings it back.
            DebugLog.log("panel", "expand suppressed (screen is off)")
            return
        }
        if (manuallyCollapsedThisSession) {
            // Deliberately not cleared by a new focus session any more. It was,
            // on the reasoning that focusing a new field means you want the
            // keyboard back -- but hiding it to watch something on the bottom
            // screen is just as common, and then it climbed back over the app
            // by itself. Hide means hide; the bubble is one tap away.
            DebugLog.log("panel", "expand suppressed (hidden by the user)")
            return
        }
        if (bottomScreenOccupant != null && !userTookScreenBack) {
            // Something else is using that screen. Never climb over it on our
            // own -- an app opening there almost always focuses a field of its
            // own, and acting on that buried the app we had just got out of the
            // way for. The bubble stays on top, so one tap brings the keyboard
            // over it deliberately.
            DebugLog.log("panel", "expand suppressed ($bottomScreenOccupant is using the bottom screen)")
            return
        }
        // Three ways a presentation can be useless while still being non-null:
        // dismissed without us hearing about it, or bound to a display that has
        // since been replaced (a zombie that reports itself visible and drawn
        // while the physical screen shows something else). Either way it would
        // swallow setExpanded() silently, so check rather than assume.
        val liveDisplayId = findSecondaryDisplay()?.displayId
        val onCurrentDisplay = bottomPresentation?.display?.displayId == liveDisplayId
        if (bottomPresentation?.isShowing != true || !onCurrentDisplay) {
            if (bottomPresentation != null && !onCurrentDisplay) {
                DebugLog.log(
                    "panel",
                    "presentation is on a stale display " +
                        "(${bottomPresentation?.display?.displayId} vs $liveDisplayId), rebuilding"
                )
            }
            // Nothing usable on the bottom screen — the display wasn't ready when
            // we last looked, an IME switch tore it down, or it was dismissed by a
            // display reconfiguration. Focusing a field is a clear request for the
            // keyboard, so build one rather than leaving a focused field with a
            // blank screen under it.
            DebugLog.log("panel", "no live presentation, creating one for this focus")
            dismissPresentation()
            refreshPresentationForCurrentIme()
            if (bottomPresentation == null) {
                DebugLog.log("panel", "still no presentation — not the selected IME, or no second display")
                return
            }
        }
        DebugLog.log("panel", "expand -> mode=$currentMode")
        panelExpanded = true
        bottomPresentation?.setExpanded(true)
        updateCursorVisibility(currentMode)
    }

    /** Collapses the panel back to its thin handle strip and clears the "manually
     * hidden" flag, since focus loss always starts a fresh session. The cursor
     * never makes sense while collapsed — there's no trackpad/nub visible to
     * justify it — regardless of which mode tab was active before collapsing. */
    fun collapse() {
        DebugLog.log("panel", "collapse")
        panelExpanded = false
        bottomPresentation?.setExpanded(false)
        CursorAccessibilityService.instance?.setCursorAllowed(false)
    }

    private fun findSecondaryDisplay(): Display? {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /** The presentation (handle strip + panel) only exists while PocketDS Keyboard
     * is the selected system IME — switching to e.g. Gboard tears it down
     * completely instead of just collapsing it, so nothing of ours is left
     * covering whatever else is on that display. */
    private fun refreshPresentationForCurrentIme() {
        val ourIme = ComponentName(this, OverlayInputMethodService::class.java)
        val currentRaw = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        // ComponentName.unflattenFromString (unlike a raw string compare) correctly
        // resolves the "pkg/.RelativeClassName" shorthand against pkg — Settings
        // doesn't always store the fully-qualified form.
        val current = currentRaw?.let { ComponentName.unflattenFromString(it) }
        if (current == ourIme) {
            if (bottomPresentation == null) {
                val display = findSecondaryDisplay()
                if (display == null) {
                    DebugLog.log("panel", "we're the selected IME but found no secondary display")
                } else {
                    secondaryDisplayId = display.displayId
                    showOnSecondaryDisplay(display, OverlayInputMethodService.isInputViewActive)
                }
            }
        } else {
            DebugLog.log("panel", "another IME selected ($currentRaw), tearing down presentation")
            dismissPresentation()
        }
    }

    /** Our own teardown, as opposed to the system pulling the presentation out
     * from under us — see [dismissingDeliberately]. */
    private fun dismissPresentation() {
        val existing = bottomPresentation ?: return
        dismissingDeliberately = true
        try {
            existing.dismiss()
        } finally {
            dismissingDeliberately = false
            bottomPresentation = null
        }
    }

    /**
     * The system dismissed the presentation itself — the bottom display was
     * removed or reconfigured, which this device does when apps are launched or
     * shuffled between screens. Drop the dead reference so it can be rebuilt,
     * and bring it straight back if input is still active, since from the user's
     * point of view the keyboard just vanished mid-use.
     */
    private fun onPresentationDismissedExternally(which: BottomScreenPresentation) {
        if (dismissingDeliberately || bottomPresentation !== which) return
        DebugLog.log("panel", "presentation dismissed by the system (display reconfigured?)")
        bottomPresentation = null

        if (!OverlayInputMethodService.isInputViewActive) return
        // Rate-limited: if recreating immediately gets dismissed again, stop
        // rather than spinning, and let the next focus rebuild it instead.
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoRecreateAt < 1000L) {
            DebugLog.log("panel", "dismissed again too soon, leaving it to the next focus")
            return
        }
        lastAutoRecreateAt = now
        val wasExpanded = panelExpanded
        refreshPresentationForCurrentIme()
        if (wasExpanded) bottomPresentation?.setExpanded(true)
    }

    private fun showOnSecondaryDisplay(display: Display, startExpanded: Boolean) {
        DebugLog.log("panel", "creating presentation on display ${display.displayId}, expanded=$startExpanded")
        dismissPresentation()
        bottomPresentation = BottomScreenPresentation(
            this, display, this, this,
            onSettingsClick = { openSettings() },
            onOnePasswordClick = { launchOnePassword() },
            onModeChanged = { mode -> updateCursorVisibility(mode) },
            onHideRequested = {
                DebugLog.log("panel", "manually hidden by user")
                manuallyCollapsedThisSession = true
                panelExpanded = false
                CursorAccessibilityService.instance?.setCursorAllowed(false)
            },
            onTemporaryCollapse = {
                // Getting out of the way of the system IME picker, which renders
                // beneath our window. Deliberately does NOT set the manual-hide
                // suppression: the user wants to switch keyboards, not to keep
                // the panel down for the rest of the focus session.
                DebugLog.log("panel", "collapsing to reveal the IME picker")
                panelExpanded = false
                CursorAccessibilityService.instance?.setCursorAllowed(false)
            },
            onExpandRequested = {
                // Tapping the bubble is the user asking for the keyboard, and
                // it outranks anything else using the screen. The occupant is
                // deliberately *not* forgotten -- doing that would have the
                // next window event rediscover it and stand down again.
                if (bottomScreenOccupant != null) {
                    DebugLog.log("panel", "bubble tapped, compact keyboard over $bottomScreenOccupant")
                    userTookScreenBack = true
                }
                // Pulled back up from the handle strip. An explicit request like
                // this also clears the manual-hide suppression — the user asking
                // for the panel is the opposite of wanting it kept down.
                manuallyCollapsedThisSession = false
                panelExpanded = true
                updateCursorVisibility(currentMode)
            }
        ).also { presentation ->
            presentation.setOnDismissListener { onPresentationDismissedExternally(presentation) }
            presentation.show()
            panelExpanded = startExpanded
            presentation.setExpanded(startExpanded)
        }
    }

    /** The cursor only makes sense while a mode that drives it is showing — otherwise
     * it just sits on screen during plain typing with nothing to do. */
    private fun updateCursorVisibility(mode: InputMode) {
        currentMode = mode
        // Deliberately not read back off bottomPresentation: this also runs while
        // a presentation is still being constructed (InputPanelView selects its
        // initial mode from its own init), at which point that field still holds
        // the previous, already-dismissed one.
        val cursorCapable = panelExpanded &&
            (mode == InputMode.TRACKPAD || mode == InputMode.NUB || mode == InputMode.SPLIT)
        CursorAccessibilityService.instance?.setCursorAllowed(cursorCapable)
    }

    /** Settings opens on the top screen — it's read-heavy, easier to work with on the big display. */
    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
        startActivity(intent, options.toBundle())
    }

    /**
     * Launches 1Password directly on the bottom screen, as requested. We can't
     * programmatically set 1Password as the system autofill service ourselves — that
     * consent step has to come from the user (or 1Password's own onboarding), since
     * letting any app silently redirect autofill would be a security hole. This just
     * gets 1Password itself up on the bottom screen to browse/copy a credential.
     */
    /**
     * Shows autofill suggestions on the bottom screen, and says whether it will,
     * which is what the framework asks.
     *
     * This is the way out of 1Password and the keyboard fighting over one
     * screen: with the credentials offered as chips above the keys, there is no
     * reason to open 1Password there at all.
     *
     * Each suggestion is drawn by the autofill service in its own process and
     * arrives asynchronously, so slots are laid out first and filled as they
     * come — they need not arrive in order, and the best match is usually first.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun showAutofillSuggestions(suggestions: List<InlineSuggestion>): Boolean {
        val panel = bottomPresentation?.panel ?: return false
        if (suggestions.isEmpty()) {
            panel.clearAutofillSuggestions()
            return false
        }

        // Sized against the screen the chips actually appear on.
        val stripWidth = panel.width.takeIf { it > 0 }
            ?: findSecondaryDisplay()?.let { display ->
                DisplayMetrics().also { @Suppress("DEPRECATION") display.getMetrics(it) }.widthPixels
            }
            ?: return false

        val chipWidth = AutofillStripSpec.chipWidthPx(
            stripWidthPx = stripWidth,
            spacingPx = AutofillStripSpec.CHIP_SPACING_PX,
            shown = suggestions.size,
            minWidthPx = AutofillStripSpec.CHIP_MIN_WIDTH_PX,
            maxWidthPx = stripWidth
        )
        panel.prepareAutofillSlots(suggestions.size, chipWidth, AutofillStripSpec.CHIP_HEIGHT_PX, AutofillStripSpec.CHIP_SPACING_PX)

        val size = Size(chipWidth, AutofillStripSpec.CHIP_HEIGHT_PX)
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(panel.context, size, mainExecutor) { view ->
                if (view == null) {
                    DebugLog.log("autofill", "suggestion $index would not draw")
                } else {
                    panel.fillAutofillSlot(index, view)
                }
            }
        }
        DebugLog.log("autofill", "showing ${suggestions.size} suggestion(s) at ${chipWidth}x$AutofillStripSpec.CHIP_HEIGHT_PX")
        return true
    }

    /**
     * Reacts to another app appearing on, or leaving, the bottom screen.
     *
     * The handover used to be set only where we launched something ourselves,
     * so an app the user opened from the launcher got sat on: the panel covers
     * that screen completely and had no idea anything was behind it.
     */
    fun onBottomScreenWindowsChanged() {
        // Window events arrive in bursts, and answering each one means walking
        // the window list across a process boundary.
        windowChangeHandler.removeCallbacks(bottomScreenCheck)
        windowChangeHandler.postDelayed(bottomScreenCheck, WINDOW_SETTLE_MS)
    }

    private val windowChangeHandler = Handler(Looper.getMainLooper())
    private val bottomScreenCheck = Runnable { checkBottomScreenOccupant() }

    private fun checkBottomScreenOccupant() {
        val displayId = findSecondaryDisplay()?.displayId ?: return
        val front = CursorAccessibilityService.instance?.frontmostAppPackage(displayId)
        val occupant = front.takeIf { BottomScreenOccupancy.isOccupant(it, packageName, homePackages) }

        when (BottomScreenOccupancy.change(was = bottomScreenOccupant, now = occupant)) {
            OccupancyChange.TAKEN -> {
                bottomScreenOccupant = occupant
                userTookScreenBack = false
                DebugLog.log("panel", "$occupant took the bottom screen, standing down to the bubble")
                collapse()
            }
            OccupancyChange.RELEASED -> {
                DebugLog.log("panel", "$bottomScreenOccupant left the bottom screen")
                bottomScreenOccupant = null
                userTookScreenBack = false
                // Nothing left behind the keyboard to make room for.
                bottomPresentation?.compact = false
            }
            OccupancyChange.UNCHANGED -> Unit
        }
    }

    /**
     * Both launchers: the usual one, and the separate launcher this device runs
     * for the bottom screen. That second one does not resolve as CATEGORY_HOME,
     * and since it is always sitting on that display, missing it would read as
     * an app permanently occupying the screen and stop the keyboard ever
     * opening.
     *
     * Worked out once — querying on every window change would be wasteful, and
     * launchers do not change under us.
     */
    private val homePackages: Set<String> by lazy {
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME)
            // Every handler, not just the default one: resolveActivity returns
            // only the launcher currently in use, and it was the *other* one
            // sitting on the bottom screen.
            .flatMap { category ->
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category),
                    0
                )
            }
            .map { it.activityInfo.packageName }
            .toSet()
            .also { DebugLog.log("panel", "launchers on this device: ${it.joinToString()}") }
    }

    /** Focus moved, so whatever was offered for the last field no longer applies. */
    fun clearAutofillSuggestions() {
        bottomPresentation?.panel?.clearAutofillSuggestions()
    }

    /**
     * Opens 1Password on the bottom screen, with the keyboard shrunk to the
     * lower half so both are usable at once.
     *
     * One caveat that is not ours to fix: 1Password cannot run its fingerprint
     * prompt down here. SystemUI destroys any biometric prompt whose app is not
     * the top running task, and it reads that task from the *top* screen, so a
     * prompt raised from this one is evicted within about 30ms, untouched. An
     * already-unlocked vault is fine; a locked one has to be unlocked on the
     * top screen first.
     */
    fun launchOnePassword() {
        // A fresh lookup rather than the cached secondaryDisplayId, which can
        // name a display that no longer exists — this device replaces the bottom
        // one out from under us.
        val displayId = findSecondaryDisplay()?.displayId ?: Display.DEFAULT_DISPLAY
        val opened = launchOnePasswordOnDisplay(this, displayId) ?: return
        if (displayId == Display.DEFAULT_DISPLAY) return

        // Deliberately expanded rather than stood down to the bubble, unlike an
        // app the user opened themselves: asking for 1Password from the
        // keyboard means wanting both at once.
        bottomScreenOccupant = opened
        userTookScreenBack = true
        // The one place the shape is chosen for the user: asking for 1Password
        // from the keyboard means wanting to see both. The strip's size button
        // flips it back.
        bottomPresentation?.compact = true
        DebugLog.log("panel", "$opened opened below, keyboard going compact")
        expand()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bottom screen panel", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketDS Input Suite")
            .setContentText("Bottom-screen keyboard/trackpad is active")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(defaultImeObserver)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        dismissPresentation()
        if (instance === this) instance = null
    }

    // --- KeyboardPanel.Listener ---

    override fun onChar(char: String) {
        OverlayInputMethodService.instance?.currentInputConnection?.commitText(char, 1)
    }

    override fun onBackspace(count: Int) {
        OverlayInputMethodService.instance?.currentInputConnection?.deleteSurroundingText(count, 0)
    }

    override fun onEnter() {
        val ic = OverlayInputMethodService.instance?.currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    override fun onSpace() {
        OverlayInputMethodService.instance?.currentInputConnection?.commitText(" ", 1)
    }

    override fun onCursorStep(steps: Int) {
        val ic = OverlayInputMethodService.instance?.currentInputConnection ?: return
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        // Arrow keys rather than setSelection: they land correctly in a web page
        // or a list, where selection offsets mean nothing to the other side.
        repeat(kotlin.math.abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    override fun onDeleteWord() {
        val ic = OverlayInputMethodService.instance?.currentInputConnection ?: return
        // There is no "delete a word" to ask for, so read back what is there and
        // work out how much of it a word accounts for.
        val before = ic.getTextBeforeCursor(WORD_LOOKBACK_CHARS, 0) ?: return
        val count = WordDelete.charsBefore(before)
        if (count > 0) ic.deleteSurroundingText(count, 0)
    }

    // --- TrackpadPanel.Listener ---

    override fun onMove(dx: Float, dy: Float) {
        CursorAccessibilityService.instance?.moveCursorBy(dx, dy)
    }

    /** Selecting text is the same drag machinery the scroll uses: a finger held
     * down and dragged. The difference is only that it follows the cursor. */

    override fun onLeftClick() {
        CursorAccessibilityService.instance?.click()
    }

    override fun onRightClick() {
        CursorAccessibilityService.instance?.rightClick()
    }

    override fun onDragStart() {
        CursorAccessibilityService.instance?.beginDrag()
    }

    override fun onDragEnd() {
        CursorAccessibilityService.instance?.endDrag()
    }

    override fun onScroll(dx: Float, dy: Float) {
        val invert = if (ScrollSettings.isInverted(this)) -1f else 1f
        CursorAccessibilityService.instance?.scrollBy(dx * invert, dy * invert)
    }

    override fun onScrollEnd() {
        CursorAccessibilityService.instance?.endScroll()
    }


    // --- FullKeyboardListener ---

    override fun onKeyEvent(keyCode: Int, metaState: Int) {
        val ic = OverlayInputMethodService.instance?.currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }
}
