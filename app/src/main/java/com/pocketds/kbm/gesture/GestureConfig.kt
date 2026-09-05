package com.pocketds.kbm.gesture

/**
 * Everything tunable about trackpad gestures, in one injectable place.
 *
 * Passed in rather than read from constants so tests can state the numbers they
 * depend on instead of inheriting whatever the shipping defaults happen to be —
 * a test that breaks because someone retuned the sensitivity is a bad test.
 */
data class GestureConfig(
    /** Cursor movement is amplified because the trackpad is smaller than the
     * screen it drives. */
    val cursorSensitivity: Float = 1.5f,
    /** Scrolling needs more than cursor movement: finger travel here has to
     * cover ground on a physically larger screen. */
    val scrollSensitivity: Float = 2.2f,
    /** How far a finger may stray and still count as a tap rather than a drag. */
    val tapSlopPx: Float = 12f,
    /**
     * A touch held longer than this is not a tap, however still it was. Without
     * a limit, resting a finger for five seconds and lifting fired a click.
     */
    val tapMaxDurationMs: Long = 500L,
    /**
     * How far the fingers must travel after the second one lands before any
     * scrolling is reported.
     *
     * Two fingers never land at the same instant or hold perfectly still, and
     * without this gate that jitter emits a scroll of a pixel or two. That is
     * worse than it sounds: downstream, a scroll becomes touch-down, a tiny
     * stroke, then lift — which is a *tap* on the other screen. It also makes a
     * two-finger tap impossible to tell from a two-finger drag. Movement inside
     * the gate is accumulated rather than discarded, so nothing is lost; it is
     * only delayed until the gesture is unambiguous.
     *
     * Kept small on purpose. At 8px it was plainly felt: every scroll started
     * with a dead patch and then lurched as the withheld movement arrived all
     * at once. It only has to outlast the pixel or two of jitter from two
     * fingers touching down, so a few pixels is enough to stay invisible.
     */
    val scrollStartSlopPx: Float = 3f,
    /**
     * How soon a second press has to follow the first to count as a double tap
     * — and so, if it is then held and dragged, as the start of a selection.
     */
    val doubleTapMs: Long = 320L
) {
    companion object {
        val DEFAULT = GestureConfig()
    }
}
