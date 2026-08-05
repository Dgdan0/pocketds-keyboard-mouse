package com.pocketds.kbm.settings

import android.content.Context

/**
 * When enabled, the bottom-screen panel behaves like a real IME: it shows itself
 * when a field is focused while our keyboard is the selected input method, hides
 * when focus is lost, and stops entirely if the user switches to a different
 * keyboard (Gboard etc). When disabled (default), the panel stays persistently
 * shown once started, regardless of focus or which IME is selected — the original
 * always-on behavior.
 */
object AutoShowSettings {
    private const val PREFS_NAME = "pocketds_settings"
    private const val KEY_AUTO_SHOW = "auto_show_on_focus"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SHOW, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SHOW, enabled)
            .apply()
    }
}
