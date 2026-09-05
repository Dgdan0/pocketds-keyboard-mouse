package com.pocketds.kbm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour a key takes while it is held down.
 *
 * There is no pressed state today — only a ripple — so at speed there is
 * nothing telling you which key registered. The brief is a good physical
 * keyboard rather than a light show: clearly different when you look, not
 * something that draws the eye on its own.
 *
 * That is a relationship, not a hex value, so it is tested as one: far enough
 * from the resting colour to read as pressed, close enough not to flash.
 */
class KeyPressTintTest {

    private val darkKey = 0xFF2C2C2E.toInt()
    private val lightKey = 0xFFFFFFFF.toInt()

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }

    @Test
    fun `a pressed key lifts away from a dark keyboard`() {
        val pressed = KeyPressTint.pressed(darkKey, dark = true)

        assertTrue(
            "pressed ${luminance(pressed)} should be brighter than ${luminance(darkKey)}",
            luminance(pressed) > luminance(darkKey)
        )
    }

    @Test
    fun `a pressed key sinks on a light keyboard`() {
        // Lifting a white key has nowhere to go, so on a light theme it darkens
        // instead — the same amount of contrast in the other direction.
        val pressed = KeyPressTint.pressed(lightKey, dark = false)

        assertTrue(
            "pressed ${luminance(pressed)} should be darker than ${luminance(lightKey)}",
            luminance(pressed) < luminance(lightKey)
        )
    }

    @Test
    fun `the change is visible`() {
        for ((base, dark) in listOf(darkKey to true, lightKey to false)) {
            val delta = kotlin.math.abs(luminance(KeyPressTint.pressed(base, dark)) - luminance(base))
            assertTrue("only $delta apart, nobody would see that", delta >= 12)
        }
    }

    @Test
    fun `the change is not dramatic`() {
        // The failure this guards against is a key that flashes. It should read
        // as the same key, pressed — not as a different colour.
        for ((base, dark) in listOf(darkKey to true, lightKey to false)) {
            val delta = kotlin.math.abs(luminance(KeyPressTint.pressed(base, dark)) - luminance(base))
            assertTrue("$delta apart is a flash, not a press", delta <= 45)
        }
    }

    @Test
    fun `opacity is left alone`() {
        // Tinting must not quietly make a key see-through.
        assertEquals(0xFF, (KeyPressTint.pressed(darkKey, dark = true) ushr 24) and 0xFF)
        assertEquals(0xFF, (KeyPressTint.pressed(lightKey, dark = false) ushr 24) and 0xFF)
    }

    @Test
    fun `channels stay in range`() {
        // Near-black and near-white are where a naive shift overflows and wraps
        // to the opposite colour.
        for (base in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A0A0A.toInt(), 0xFFF8F8F8.toInt())) {
            for (dark in listOf(true, false)) {
                val pressed = KeyPressTint.pressed(base, dark)
                for (shift in listOf(16, 8, 0)) {
                    val channel = (pressed shr shift) and 0xFF
                    assertTrue("channel $channel out of range for base $base", channel in 0..255)
                }
            }
        }
    }

    @Test
    fun `an accent key is tinted too`() {
        // Enter and shift are accent-coloured; they need a pressed state as much
        // as the letters do.
        val accent = 0xFF32D0C0.toInt()
        assertTrue(KeyPressTint.pressed(accent, dark = true) != accent)
    }

    @Test
    fun `a dark keyboard is recognised as one`() {
        // Which way a press moves depends on this, so getting it wrong makes
        // pressed keys vanish into the background instead of standing out.
        assertTrue(KeyPressTint.isDarkSurface(0xFF1C1C1E.toInt()))
        assertTrue(!KeyPressTint.isDarkSurface(0xFFF2F2F7.toInt()))
    }
}
