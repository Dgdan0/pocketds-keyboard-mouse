package com.pocketds.kbm.ui

import android.content.Context
import android.content.res.Configuration
import com.pocketds.kbm.settings.ThemeSettings

data class PocketColors(
    val background: Int,
    val keySurface: Int,
    val keySurfaceRipple: Int,
    val keyText: Int,
    val mutedText: Int,
    val accent: Int,
    val accentText: Int,
    val stripBackground: Int
)

object Theme {
    private val LIGHT = PocketColors(
        background = 0xFFF1F1F4.toInt(),
        keySurface = 0xFFFFFFFF.toInt(),
        keySurfaceRipple = 0xFFD8D8DE.toInt(),
        keyText = 0xFF1B1B1F.toInt(),
        mutedText = 0xFF6E6E76.toInt(),
        accent = 0xFF0FADA0.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        stripBackground = 0xFFE3E3E8.toInt()
    )

    private val DARK = PocketColors(
        background = 0xFF15151A.toInt(),
        keySurface = 0xFF25252B.toInt(),
        keySurfaceRipple = 0xFF3A3A42.toInt(),
        keyText = 0xFFF1F1F3.toInt(),
        mutedText = 0xFF9A9AA4.toInt(),
        accent = 0xFF2BE0CE.toInt(),
        accentText = 0xFF0A0A0B.toInt(),
        stripBackground = 0xFF1D1D22.toInt()
    )

    fun colors(context: Context): PocketColors = if (isDark(context)) DARK else LIGHT

    fun isDark(context: Context): Boolean = when (ThemeSettings.getMode(context)) {
        ThemeSettings.Mode.LIGHT -> false
        ThemeSettings.Mode.DARK -> true
        ThemeSettings.Mode.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
