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
    /** Views that take a plain click often handle a long one without saying so. */
    val clickable: Boolean = false,
    /** Text you can edit, where a long click is about *which word*. */
    val editable: Boolean = false,
    /** How deep in the view tree, so the innermost match can win. */
    val depth: Int = 0
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
 * Two things make the choice less obvious than "what is under the point".
 *
 * A point lands inside every ancestor as well as the view you mean, so
 * containment alone would happily pick the whole window. Innermost wins, and
 * the smaller one wins where things are nested equally.
 *
 * And a long click carries no coordinates: it acts on a *view*, not a point.
 * For a button that is the same thing, but asking a web page or a whole list to
 * long-click itself acts somewhere else entirely — or reports success having
 * done nothing, which is worse, because it stops the caller falling back.
 * Anything container-sized is therefore left to the held finger, which is
 * slower but lands where the cursor actually is.
 */
object LongClickTarget {

    /**
     * Everything worth asking, innermost first.
     *
     * A list rather than one answer because `isLongClickable` is advertising
     * rather than truth — asking is the only way to find out, and the action
     * reports back whether it was handled, so the caller can work down the list.
     *
     * @param maxArea largest a view may be and still be an unambiguous target.
     */
    fun rank(
        candidates: List<NodeCandidate>,
        x: Int,
        y: Int,
        maxArea: Int = Int.MAX_VALUE
    ): List<NodeCandidate> =
        candidates
            .filter {
                (it.longClickable || it.clickable) &&
                    // Text needs its caret put in the right place first, so it
                    // is offered through textFieldAt instead. Left here it
                    // would select at the wrong word *and report success*,
                    // which would stop anything better being tried.
                    !it.editable &&
                    it.area > 0 &&
                    it.area <= maxArea &&
                    it.contains(x, y)
            }
            // Innermost first; among equals, the one that says it takes a long
            // click before the one that merely might, then the smaller.
            .sortedWith(
                compareByDescending<NodeCandidate> { it.depth }
                    .thenByDescending { it.longClickable }
                    .thenBy { it.area }
            )

    fun pick(candidates: List<NodeCandidate>, x: Int, y: Int): NodeCandidate? =
        rank(candidates, x, y).firstOrNull()

    /**
     * The innermost editable field under the point, if there is one.
     *
     * Text is the one case where a coordinate-free long click is meaningless on
     * its own: it selects whatever word the caret is already on. Given a caret
     * placed where the cursor is pointing, though, it does exactly the right
     * thing — so the caller taps first and then asks.
     */
    fun textFieldAt(candidates: List<NodeCandidate>, x: Int, y: Int): NodeCandidate? =
        candidates
            .filter { it.editable && it.area > 0 && it.contains(x, y) }
            .maxWithOrNull(compareBy({ it.depth }, { -it.area }))
}
