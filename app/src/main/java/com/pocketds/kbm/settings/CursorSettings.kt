package com.pocketds.kbm.settings

import android.content.Context

object CursorSettings {
    private const val PREFS_NAME = "pocketds_settings"
    private const val KEY_IDLE_HIDE_SECONDS = "cursor_idle_hide_seconds"
    private const val KEY_HIDE_ON_SCREEN_TOUCH = "cursor_hide_on_screen_touch"

    /** 0 means "never hide on idle". */
    const val IDLE_NEVER = 0
    private const val DEFAULT_IDLE_SECONDS = 10

    val idleOptionsSeconds = listOf(5, 10, 15, 30, IDLE_NEVER)

    fun idleHideSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_IDLE_HIDE_SECONDS, DEFAULT_IDLE_SECONDS)

    fun setIdleHideSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_IDLE_HIDE_SECONDS, seconds)
            .apply()
    }

    fun hideOnScreenTouch(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_ON_SCREEN_TOUCH, true)

    fun setHideOnScreenTouch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_ON_SCREEN_TOUCH, enabled)
            .apply()
    }
}
