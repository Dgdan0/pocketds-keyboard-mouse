package com.pocketds.kbm.gesture

/**
 * One touch event, reduced to plain values so gesture logic can be reasoned
 * about — and tested — without `MotionEvent`, a `View`, or a device.
 *
 * Nothing in this package may import `android.*`. That restriction is the whole
 * point: it is what lets the interesting decisions run on the JVM in
 * milliseconds instead of needing a rebuild, an install, and someone reading a
 * screen back to me.
 */
enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/** Sentinel for samples where no single pointer is the subject of the action. */
const val NO_POINTER = -1

data class Pointer(val id: Int, val x: Float, val y: Float)

data class TouchSample(
    val action: TouchAction,
    /**
     * Every pointer currently on the surface, mirroring `MotionEvent` exactly —
     * which means that for [TouchAction.POINTER_UP] and [TouchAction.UP] this
     * **still contains the pointer that is lifting**.
     *
     * Kept faithful to `MotionEvent` on purpose: the adapter that builds these
     * must be a mechanical translation with no decisions of its own, because it
     * is the one piece that cannot be unit-tested. Anything it has to decide is
     * untested code.
     */
    val pointers: List<Pointer>,
    /**
     * The pointer this action refers to — which one went down or lifted.
     * [NO_POINTER] for [TouchAction.MOVE] and [TouchAction.CANCEL], where it has
     * no meaning.
     */
    val actionPointerId: Int,
    val eventTimeMs: Long
) {
    val pointerCount: Int get() = pointers.size

    fun pointer(id: Int): Pointer? = pointers.firstOrNull { it.id == id }
}
