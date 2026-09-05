package com.pocketds.kbm.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Noticing that something else is using the bottom screen.
 *
 * The panel covers that screen completely, so anything running there is behind
 * it. That was handled for 1Password only because we launched it ourselves and
 * knew — open Jellyfin from the launcher instead and the panel sits on top of it
 * with no idea anything is there.
 */
class BottomScreenOccupancyTest {

    private val ours = "com.pocketds.kbm"

    /**
     * Both launchers. This device runs a second one of its own for the bottom
     * screen — com.ayaneo.gamewindow's SecondaryLauncherActivity — and it does
     * not resolve as CATEGORY_HOME. Miss it and it reads as an app permanently
     * occupying that screen, which would stop the keyboard ever opening.
     */
    private val home = setOf("com.android.launcher3", "com.ayaneo.gamewindow")

    @Test
    fun `another app is an occupant`() {
        assertTrue(BottomScreenOccupancy.isOccupant("org.jellyfin.mobile", ours, home))
    }

    @Test
    fun `our own panel is not an occupant`() {
        // The panel is what would be getting out of the way; it cannot be the
        // reason to.
        assertFalse(BottomScreenOccupancy.isOccupant(ours, ours, home))
    }

    @Test
    fun `the launcher is not an occupant`() {
        // A home screen behind the panel is the resting state, not something
        // the user is trying to look at.
        assertFalse(BottomScreenOccupancy.isOccupant("com.android.launcher3", ours, home))
    }

    @Test
    fun `the bottom screen's own launcher is not an occupant`() {
        // The one that would have broken everything: it is always there, so
        // treating it as an app would suppress the keyboard permanently.
        assertFalse(BottomScreenOccupancy.isOccupant("com.ayaneo.gamewindow", ours, home))
    }

    @Test
    fun `an empty screen is not an occupant`() {
        assertFalse(BottomScreenOccupancy.isOccupant(null, ours, home))
    }

    @Test
    fun `an unknown home package does not make everything an occupant`() {
        // Resolving the launchers can fail; that must not turn the home screen
        // into a reason to hide the keyboard forever.
        assertTrue(BottomScreenOccupancy.isOccupant("org.jellyfin.mobile", ours, emptySet()))
    }

    // --- reacting to a change ---------------------------------------------

    @Test
    fun `an app arriving takes the screen`() {
        assertEquals(
            OccupancyChange.TAKEN,
            BottomScreenOccupancy.change(was = null, now = "org.jellyfin.mobile")
        )
    }

    @Test
    fun `the app leaving gives the screen back`() {
        assertEquals(
            OccupancyChange.RELEASED,
            BottomScreenOccupancy.change(was = "org.jellyfin.mobile", now = null)
        )
    }

    @Test
    fun `one app replacing another still counts as taken`() {
        assertEquals(
            OccupancyChange.TAKEN,
            BottomScreenOccupancy.change(was = "org.jellyfin.mobile", now = "com.onepassword.android")
        )
    }

    @Test
    fun `the same app still there changes nothing`() {
        // Window events fire constantly. Acting on every one would re-collapse
        // the panel the moment the user pulled it up over the app on purpose.
        assertEquals(
            OccupancyChange.UNCHANGED,
            BottomScreenOccupancy.change(was = "org.jellyfin.mobile", now = "org.jellyfin.mobile")
        )
    }

    @Test
    fun `an empty screen staying empty changes nothing`() {
        assertEquals(OccupancyChange.UNCHANGED, BottomScreenOccupancy.change(was = null, now = null))
    }
}
