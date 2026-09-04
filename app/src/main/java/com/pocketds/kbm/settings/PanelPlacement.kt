package com.pocketds.kbm.settings

import android.content.Context

/**
 * Where the user last parked the floating bubble, so it stays put across focus
 * changes, app restarts and reboots instead of springing back to a default
 * corner every time.
 */
object PanelPlacement {
    private const val PREFS_NAME = "pocketds_settings"
    private const val KEY_ON_LEFT = "bubble_on_left"
    private const val KEY_VERTICAL_FRACTION = "bubble_vertical_fraction"
    private const val KEY_TUCKED = "bubble_tucked"

    fun isOnLeft(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON_LEFT, true)

    /** Stored as a fraction of the screen height rather than pixels, so it lands
     * in the same visual spot regardless of the display it ends up on. */
    fun verticalFraction(context: Context): Float =
        prefs(context).getFloat(KEY_VERTICAL_FRACTION, 0.35f)

    fun isTucked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TUCKED, false)

    fun save(context: Context, onLeft: Boolean, verticalFraction: Float, tucked: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ON_LEFT, onLeft)
            .putFloat(KEY_VERTICAL_FRACTION, verticalFraction.coerceIn(0f, 1f))
            .putBoolean(KEY_TUCKED, tucked)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
