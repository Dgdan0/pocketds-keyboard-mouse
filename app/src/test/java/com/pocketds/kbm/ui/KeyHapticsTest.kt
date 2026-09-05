package com.pocketds.kbm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a key should buzz, and how hard.
 *
 * Whether the phone actually vibrates cannot be tested off a device, so what is
 * tested is the decision: the strengths differ from each other, off means
 * silent, and a held backspace does not turn into a continuous rattle.
 */
class KeyHapticsTest {

    @Test
    fun `off means nothing at all`() {
        assertNull(KeyHaptics.effectFor(HapticStrength.OFF))
    }

    @Test
    fun `light and strong both do something`() {
        assertTrue(KeyHaptics.effectFor(HapticStrength.LIGHT) != null)
        assertTrue(KeyHaptics.effectFor(HapticStrength.STRONG) != null)
    }

    @Test
    fun `light and strong are not the same thing`() {
        // A setting that changes nothing is worse than no setting.
        assertNotEquals(
            KeyHaptics.effectFor(HapticStrength.LIGHT),
            KeyHaptics.effectFor(HapticStrength.STRONG)
        )
    }

    // --- key repeat --------------------------------------------------------

    @Test
    fun `the first press of a held key buzzes`() {
        val gate = KeyHaptics.RepeatGate()

        assertTrue(gate.allow(atMs = 0))
    }

    @Test
    fun `a fast repeat does not buzz every time`() {
        // Backspace repeats several times a second; buzzing on each is a rattle
        // against the palm, not feedback.
        val gate = KeyHaptics.RepeatGate()
        gate.allow(atMs = 0)

        assertFalse(gate.allow(atMs = 30))
        assertFalse(gate.allow(atMs = 60))
    }

    @Test
    fun `a slow repeat still buzzes`() {
        val gate = KeyHaptics.RepeatGate()
        gate.allow(atMs = 0)

        assertTrue(gate.allow(atMs = 500))
    }

    @Test
    fun `letting go and pressing again always buzzes`() {
        // Two deliberate presses are two presses, however quickly they follow
        // one another — that is typing fast, not a key repeating.
        val gate = KeyHaptics.RepeatGate()
        gate.allow(atMs = 0)
        gate.release()

        assertTrue(gate.allow(atMs = 20))
    }

    @Test
    fun `the settings round-trip`() {
        for (strength in HapticStrength.entries) {
            assertEquals(strength, HapticStrength.fromStored(strength.stored))
        }
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        // Subtle by default: it is a keyboard, not a game controller.
        assertEquals(HapticStrength.LIGHT, HapticStrength.fromStored("nonsense"))
    }
}
