package com.pocketds.kbm.ime

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import com.pocketds.kbm.MainActivity
import com.pocketds.kbm.accessibility.CursorAccessibilityService
import com.pocketds.kbm.settings.ScrollSettings

/**
 * Owns the bottom-screen Presentation for the lifetime of the app, independent of
 * IME focus churn. Keyboard actions forward to whichever field currently has an
 * active InputConnection (via OverlayInputMethodService); trackpad actions forward
 * to CursorAccessibilityService. Both are no-ops if their target isn't active yet,
 * which just means "not focused"/"accessibility not enabled" rather than a crash.
 */
class BottomPanelService : Service(), FullKeyboardListener, TrackpadPanel.Listener {

    private var bottomPresentation: BottomScreenPresentation? = null
    private var secondaryDisplayId: Int? = null

    companion object {
        private const val CHANNEL_ID = "pocketds_bottom_panel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PocketDS"
        private const val ONEPASSWORD_PACKAGE = "com.onepassword.android"
        const val ACTION_REFRESH_THEME = "com.pocketds.kbm.ACTION_REFRESH_THEME"
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        findSecondaryDisplay()?.let {
            secondaryDisplayId = it.displayId
            showOnSecondaryDisplay(it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_THEME) {
            findSecondaryDisplay()?.let { showOnSecondaryDisplay(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun findSecondaryDisplay(): Display? {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun showOnSecondaryDisplay(display: Display) {
        bottomPresentation?.dismiss()
        bottomPresentation = BottomScreenPresentation(
            this, display, this, this,
            onSettingsClick = { openSettings() },
            onOnePasswordClick = { launchOnePassword() }
        ).also { it.show() }
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
        bottomPresentation?.dismiss()
        bottomPresentation = null
    }

    // --- KeyboardPanel.Listener ---

    override fun onChar(char: String) {
        OverlayInputMethodService.instance?.currentInputConnection?.commitText(char, 1)
    }

    override fun onBackspace() {
        OverlayInputMethodService.instance?.currentInputConnection?.deleteSurroundingText(1, 0)
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
