package com.pocketds.kbm.settings

import android.content.Context

object ScrollSettings {
    private const val PREFS_NAME = "pocketds_settings"
    private const val KEY_INVERT_SCROLL = "invert_scroll"

    fun isInverted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INVERT_SCROLL, false)

    fun setInverted(context: Context, inverted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INVERT_SCROLL, inverted)
            .apply()
    }
}
