package com.pocketds.kbm.ime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.pocketds.kbm.debug.DebugLog

/**
 * Gets the bottom-screen panel running without anyone having to open the app
 * first.
 *
 * Otherwise the panel only exists once something has started BottomPanelService
 * — which normally means launching the app — so after a reboot you could select
 * PocketDS Keyboard, tap a field, and get a focused field with a blank bottom
 * screen. The input method itself can start the service on demand, but Android
 * restricts starting foreground services from the background, so relying on
 * that alone is fragile.
 *
 * Only starts the panel when PocketDS Keyboard is actually the selected input
 * method; there's no reason to hold a foreground service otherwise.
 */
class PanelBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val ourIme = ComponentName(context, OverlayInputMethodService::class.java)
        val currentRaw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )
        // Resolved via ComponentName rather than compared as a string, since
        // Settings may hold either the fully-qualified or the "pkg/.Class" form.
        val current = currentRaw?.let { ComponentName.unflattenFromString(it) }
        if (current != ourIme) {
            DebugLog.log("panel", "$action: not the selected IME, leaving the panel alone")
            return
        }

        DebugLog.log("panel", "$action: starting the panel service")
        val serviceIntent = Intent(context, BottomPanelService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            DebugLog.log("panel", "could not start panel service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
