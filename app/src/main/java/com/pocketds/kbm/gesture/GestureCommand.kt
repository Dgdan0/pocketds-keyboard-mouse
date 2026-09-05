package com.pocketds.kbm.gesture

/**
 * What a gesture decided should happen.
 *
 * Returned from the recogniser rather than pushed into a listener, which is what
 * keeps the tests free of any mocking framework: a test feeds touch samples in
 * and compares the returned list against expected values.
 */
sealed interface GestureCommand {
    data class MoveCursor(val dx: Float, val dy: Float) : GestureCommand
    data class Scroll(val dx: Float, val dy: Float) : GestureCommand
    data object ScrollEnd : GestureCommand
    data object LeftClick : GestureCommand
    data object RightClick : GestureCommand

    /** Press and hold at the cursor: the start of dragging out a text selection. */
    data object DragStart : GestureCommand

    /** Let go, ending a selection drag. */
    data object DragEnd : GestureCommand
}
