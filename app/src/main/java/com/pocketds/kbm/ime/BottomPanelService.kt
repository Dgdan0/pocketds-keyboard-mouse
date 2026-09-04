package com.pocketds.kbm.ime

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import com.pocketds.kbm.MainActivity
import com.pocketds.kbm.accessibility.CursorAccessibilityService
import com.pocketds.kbm.debug.DebugLog
import com.pocketds.kbm.layout.InputMode
import com.pocketds.kbm.settings.ScrollSettings

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

    companion object {
        var instance: BottomPanelService? = null
            private set

        private const val CHANNEL_ID = "pocketds_bottom_panel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PocketDS"
        private const val ONEPASSWORD_PACKAGE = "com.onepassword.android"
        const val ACTION_REFRESH_THEME = "com.pocketds.kbm.ACTION_REFRESH_THEME"
    }

    private val defaultImeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshPresentationForCurrentIme()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, defaultImeObserver
        )
        refreshPresentationForCurrentIme()
    }

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
    fun expand(freshSession: Boolean = false) {
        if (freshSession && manuallyCollapsedThisSession) {
            DebugLog.log("panel", "new focus session, clearing manual-hide suppression")
            manuallyCollapsedThisSession = false
        }
        if (manuallyCollapsedThisSession) {
            DebugLog.log("panel", "expand suppressed (manually hidden this session)")
            return
        }
        if (bottomPresentation == null) {
            // The service is alive but has nothing on the bottom screen — e.g. the
            // display wasn't ready when we last looked, or an IME switch tore the
            // presentation down and we've since been selected again. Focusing a
            // field is a clear request for the keyboard, so build it now rather
            // than leaving the user with a focused field and a blank screen.
            DebugLog.log("panel", "no presentation yet, creating one for this focus")
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
        manuallyCollapsedThisSession = false
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
            bottomPresentation?.dismiss()
            bottomPresentation = null
        }
    }

    private fun showOnSecondaryDisplay(display: Display, startExpanded: Boolean) {
        DebugLog.log("panel", "creating presentation on display ${display.displayId}, expanded=$startExpanded")
        bottomPresentation?.dismiss()
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
                // Pulled back up from the handle strip. An explicit request like
                // this also clears the manual-hide suppression — the user asking
                // for the panel is the opposite of wanting it kept down.
                manuallyCollapsedThisSession = false
                panelExpanded = true
                updateCursorVisibility(currentMode)
            }
        ).also {
            it.show()
            panelExpanded = startExpanded
            it.setExpanded(startExpanded)
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
    private fun launchOnePassword() {
        val displayId = secondaryDisplayId ?: Display.DEFAULT_DISPLAY
        val intent = packageManager.getLaunchIntentForPackage(ONEPASSWORD_PACKAGE)
        if (intent == null) {
            Log.w(TAG, "1Password ($ONEPASSWORD_PACKAGE) isn't installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        startActivity(intent, options.toBundle())
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
        bottomPresentation?.dismiss()
        bottomPresentation = null
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

    // --- TrackpadPanel.Listener ---

    override fun onMove(dx: Float, dy: Float) {
        CursorAccessibilityService.instance?.moveCursorBy(dx, dy)
    }

    override fun onLeftClick() {
        CursorAccessibilityService.instance?.click()
    }

    override fun onRightClick() {
        CursorAccessibilityService.instance?.rightClick()
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
