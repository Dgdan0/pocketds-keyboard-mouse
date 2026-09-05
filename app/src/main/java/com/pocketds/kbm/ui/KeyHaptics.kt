package com.pocketds.kbm.ui

import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticStrength(val stored: String, val label: String) {
    OFF("off", "Off"),
    LIGHT("light", "Light"),
    STRONG("strong", "Strong");

    companion object {
        /** Subtle by default: it is a keyboard, not a game controller. */
        val DEFAULT = LIGHT

        fun fromStored(value: String?): HapticStrength =
            entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

/**
 * Key feedback, through the system's own haptic constants.
 *
 * Deliberately not the Vibrator API, which would want the VIBRATE permission
 * for a keyboard — and would also bypass the device-wide touch-feedback
 * setting, which a user who has turned haptics off meant to apply here too.
 */
object KeyHaptics {

    /** The constant to feed [View.performHapticFeedback], or null for silence. */
    fun effectFor(strength: HapticStrength): Int? = when (strength) {
        HapticStrength.OFF -> null
        // The system's own keyboard tick: what every other keyboard on the
        // device feels like, which is the point.
        HapticStrength.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
        HapticStrength.STRONG -> HapticFeedbackConstants.VIRTUAL_KEY
    }

    fun perform(view: View, strength: HapticStrength) {
        val effect = effectFor(strength) ?: return
        view.performHapticFeedback(effect)
    }

    /**
     * Keeps a held key from rattling.
     *
     * Backspace repeats several times a second. Buzzing on every repeat is a
     * continuous vibration against the palm rather than feedback, so repeats
     * are spaced out — while two deliberate presses, however fast, both count,
     * because that is typing rather than a key repeating.
     */
    class RepeatGate(private val minGapMs: Long = 120L) {
        private var lastAtMs: Long? = null

        fun allow(atMs: Long): Boolean {
            val last = lastAtMs
            if (last != null && atMs - last < minGapMs) return false
            lastAtMs = atMs
            return true
        }

        /** The key came up, so the next press starts over. */
        fun release() {
            lastAtMs = null
        }
    }
}
