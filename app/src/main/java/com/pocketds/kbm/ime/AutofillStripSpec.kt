package com.pocketds.kbm.ime

/** How big autofill chips may be, and how many to ask for. */
data class ChipSizing(
    val minWidthPx: Int,
    val maxWidthPx: Int,
    val heightPx: Int,
    val count: Int
)

/**
 * Works out how much room to offer autofill suggestions.
 *
 * The framework asks before any suggestion exists — how many, and how big each
 * may be — and the autofill service renders its chips to fit what it is told.
 * Ask for more than the strip holds and they are cut off; ask for too few and a
 * saved login goes unoffered.
 *
 * Derived from the strip's real width rather than fixed, because the strip
 * spans the bottom screen, and that screen is not the one this code runs
 * against by default.
 */
object AutofillStripSpec {

    fun sizing(
        stripWidthPx: Int,
        chipHeightPx: Int,
        spacingPx: Int,
        minChipWidthPx: Int,
        maxSuggestions: Int
    ): ChipSizing {
        // n chips need n widths plus the gaps between them.
        val fit = (stripWidthPx + spacingPx) / (minChipWidthPx + spacingPx)
        // Never zero: the framework reads that as wanting no suggestions at
        // all, and one chip that scrolls beats none.
        val count = fit.coerceIn(1, maxSuggestions)

        return ChipSizing(
            minWidthPx = minChipWidthPx.coerceAtMost(stripWidthPx),
            // A lone suggestion may use the whole strip, so a long username is
            // not truncated to a chip's worth of room for no reason.
            maxWidthPx = stripWidthPx,
            heightPx = chipHeightPx,
            count = count
        )
    }
}
