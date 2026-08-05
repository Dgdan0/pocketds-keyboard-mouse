package com.pocketds.kbm.settings

import android.content.Context

object ThemeSettings {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private const val PREFS_NAME = "pocketds_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getMode(context: Context): Mode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, Mode.SYSTEM.name)
        return runCatching { Mode.valueOf(name ?: Mode.SYSTEM.name) }.getOrDefault(Mode.SYSTEM)
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }
}
