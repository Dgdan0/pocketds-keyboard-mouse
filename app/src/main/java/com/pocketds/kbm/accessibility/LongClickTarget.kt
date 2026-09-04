package com.pocketds.kbm.accessibility

/**
 * One view considered as a long-click target, flattened to plain numbers so the
 * choice can be tested without an `AccessibilityNodeInfo` or a device.
 *
 * [index] refers back to the node this was built from, since the caller has to
 * act on the real thing once a choice is made.
 */
data class NodeCandidate(
    val index: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val longClickable: Boolean,
    /** How deep in the view tree, so the innermost match can win. */
    val depth: Int
) {
    val area: Int get() = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)

    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
}

/**
 * Picks what the cursor is pointing at.
 *
 * Asking a view to perform a long-click is instant, where holding a synthetic
 * finger down has to outlast the system's long-press threshold — around half a
 * second that the user feels on every right-click.
 *
 * The subtlety is that a point lands inside every ancestor as well as the view
 * you mean, so "contains the point" would happily pick the whole window.
 * Innermost wins, and where things are nested equally the smaller one does —
 * but only among views that will actually handle a long click, because the
 * deepest thing under a finger is often a label inside the row that owns the
 * gesture.
 */
object LongClickTarget {

    fun pick(candidates: List<NodeCandidate>, x: Int, y: Int): NodeCandidate? =
        candidates
            .filter { it.longClickable && it.area > 0 && it.contains(x, y) }
            .maxWithOrNull(compareBy({ it.depth }, { -it.area }))
}
