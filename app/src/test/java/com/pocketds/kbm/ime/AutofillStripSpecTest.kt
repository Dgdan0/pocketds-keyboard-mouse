package com.pocketds.kbm.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much room to offer autofill suggestions.
 *
 * The framework asks up front, before any suggestion exists: how many, and how
 * big may each be. The autofill service then renders its chips to fit. Ask for
 * more than the strip holds and they are cut off; ask for too few and a saved
 * login goes unoffered.
 */
class AutofillStripSpecTest {

    /** The bottom screen, which is what the strip actually spans. */
    private val stripWidth = 1024

    @Test
    fun `a wide strip offers the full number of suggestions`() {
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = stripWidth,
            chipHeightPx = 72,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(4, sizing.count)
    }

    @Test
    fun `a narrow strip offers only what fits`() {
        // 400px holds two 180px chips with spacing between them, not four.
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = 400,
            chipHeightPx = 72,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(2, sizing.count)
    }

    @Test
    fun `spacing counts against the room available`() {
        // Two 180px chips is 360px of chip, but they cannot sit flush: at 8px
        // apart they need 368, and the same pair needs 380 at 20px apart.
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = 368,
            chipHeightPx = 72,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(2, sizing.count)

        val tighter = AutofillStripSpec.sizing(
            stripWidthPx = 368,
            chipHeightPx = 72,
            spacingPx = 20,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(1, tighter.count)
    }

    @Test
    fun `even an implausibly narrow strip still offers one`() {
        // Never ask for zero: the framework takes that as "no suggestions
        // wanted", and one chip scrolled sideways still beats none.
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = 50,
            chipHeightPx = 72,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(1, sizing.count)
    }

    @Test
    fun `one suggestion may use the whole strip`() {
        // A long username should not be truncated into a chip's worth of room
        // when it is the only thing offered.
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = stripWidth,
            chipHeightPx = 72,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(stripWidth, sizing.maxWidthPx)
    }

    @Test
    fun `a chip is never allowed to be wider than the strip`() {
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = 120,
            chipHeightPx = 72,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertTrue(
            "min ${sizing.minWidthPx} must fit inside 120",
            sizing.minWidthPx <= 120
        )
        assertTrue("max ${sizing.maxWidthPx} must fit inside 120", sizing.maxWidthPx <= 120)
    }

    @Test
    fun `the requested height is passed through`() {
        val sizing = AutofillStripSpec.sizing(
            stripWidthPx = stripWidth,
            chipHeightPx = 96,
            spacingPx = 8,
            minChipWidthPx = 180,
            maxSuggestions = 4
        )

        assertEquals(96, sizing.heightPx)
    }

    // --- how wide each chip is drawn --------------------------------------

    @Test
    fun `chips share the strip between them`() {
        // Four chips and three gaps in 1024: (1024 - 36) / 4.
        val width = AutofillStripSpec.chipWidthPx(
            stripWidthPx = stripWidth,
            spacingPx = 12,
            shown = 4,
            minWidthPx = 180,
            maxWidthPx = stripWidth
        )

        assertEquals(247, width)
    }

    @Test
    fun `a lone chip gets the whole strip`() {
        val width = AutofillStripSpec.chipWidthPx(
            stripWidthPx = stripWidth,
            spacingPx = 12,
            shown = 1,
            minWidthPx = 180,
            maxWidthPx = stripWidth
        )

        assertEquals(stripWidth, width)
    }

    @Test
    fun `chips are never drawn narrower than the minimum promised`() {
        // The framework was told a minimum, and rendering below it is outside
        // what the autofill service agreed to draw.
        val width = AutofillStripSpec.chipWidthPx(
            stripWidthPx = stripWidth,
            spacingPx = 12,
            shown = 20,
            minWidthPx = 180,
            maxWidthPx = stripWidth
        )

        assertEquals(180, width)
    }

    @Test
    fun `nothing to show asks for no width`() {
        val width = AutofillStripSpec.chipWidthPx(
            stripWidthPx = stripWidth,
            spacingPx = 12,
            shown = 0,
            minWidthPx = 180,
            maxWidthPx = stripWidth
        )

        assertEquals(0, width)
    }
}
