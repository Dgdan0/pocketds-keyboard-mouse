package com.pocketds.kbm.settings

import android.content.Context
import com.pocketds.kbm.ui.HapticStrength

object HapticSettings {
    private const val PREFS_NAME = "pocketds_settings"
    private const val KEY_STRENGTH = "haptic_strength"

    fun strength(context: Context): HapticStrength =
        HapticStrength.fromStored(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STRENGTH, null)
        )

    fun setStrength(context: Context, strength: HapticStrength) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STRENGTH, strength.stored)
            .apply()
    }
}
