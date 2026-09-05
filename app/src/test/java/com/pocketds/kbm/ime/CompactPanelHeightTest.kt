package com.pocketds.kbm.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How tall the keyboard is when something else is using the bottom screen.
 *
 * A phone keyboard takes the lower part of the screen and leaves the app
 * visible above it. Ours takes the whole screen, which is right when nothing
 * else is there and wrong the moment something is.
 *
 * The number that matters is not the keyboard's — it is how much is left for
 * the app. A keyboard that grew until the app was a sliver would defeat the
 * point of shrinking it at all.
 */
class CompactPanelHeightTest {

    /** The bottom screen. */
    private val screen = 768

    @Test
    fun `takes its share of the screen`() {
        assertEquals(
            345,
            CompactPanelHeight.forDisplay(
                displayHeightPx = screen,
                fraction = 0.45f,
                minKeyboardPx = 200,
                minAppPx = 200
            )
        )
    }

    @Test
    fun `leaves the app its minimum even if the keyboard wants more`() {
        // The app is the reason the keyboard shrank; it does not get squeezed
        // out by a greedy fraction.
        val height = CompactPanelHeight.forDisplay(
            displayHeightPx = screen,
            fraction = 0.9f,
            minKeyboardPx = 200,
            minAppPx = 300
        )

        assertEquals(468, height)
        assertTrue("the app keeps at least 300px", screen - height >= 300)
    }

    @Test
    fun `a small fraction still leaves a usable keyboard`() {
        assertEquals(
            200,
            CompactPanelHeight.forDisplay(
                displayHeightPx = screen,
                fraction = 0.1f,
                minKeyboardPx = 200,
                minAppPx = 200
            )
        )
    }

    @Test
    fun `on a screen too small for both, the app still gets its share`() {
        // Nothing can satisfy a 200px keyboard and a 200px app in 300px. The app
        // wins, because seeing it is the whole reason for compact mode -- and a
        // keyboard is useless if it hides what you are typing into.
        val height = CompactPanelHeight.forDisplay(
            displayHeightPx = 300,
            fraction = 0.45f,
            minKeyboardPx = 200,
            minAppPx = 200
        )

        assertEquals(100, height)
        assertTrue("never taller than the screen", height <= 300)
    }

    @Test
    fun `never returns a negative height`() {
        // A display smaller than the app's minimum would otherwise produce a
        // negative window height, which the window manager treats as garbage.
        val height = CompactPanelHeight.forDisplay(
            displayHeightPx = 100,
            fraction = 0.45f,
            minKeyboardPx = 200,
            minAppPx = 300
        )

        assertTrue("got $height", height in 0..100)
    }
}
