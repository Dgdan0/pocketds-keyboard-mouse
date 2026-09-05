package com.pocketds.kbm.ime

/**
 * How tall the keyboard is while something else is using the bottom screen.
 *
 * A phone keyboard takes the lower part of the screen and leaves the app above
 * it visible. Ours takes the whole screen, which is right when nothing else is
 * there and wrong the moment something is.
 *
 * The governing number is not the keyboard's height but how much is left over:
 * the app is the reason the keyboard shrank, so its share is the one that wins
 * when they cannot both be satisfied.
 */
object CompactPanelHeight {

    fun forDisplay(
        displayHeightPx: Int,
        fraction: Float,
        minKeyboardPx: Int,
        minAppPx: Int
    ): Int {
        val wanted = (displayHeightPx * fraction).toInt().coerceAtLeast(minKeyboardPx)
        // Whatever it asks for, the app keeps its share — and on a display too
        // small for both, that leaves the keyboard with the remainder rather
        // than a negative height the window manager would reject.
        val ceiling = displayHeightPx - minAppPx
        return wanted.coerceAtMost(ceiling).coerceIn(0, displayHeightPx)
    }
}
