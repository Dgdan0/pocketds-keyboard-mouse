package com.pocketds.kbm.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choosing what to long-click under the cursor.
 *
 * Asking the view under the pointer to perform a long-click is instant, unlike
 * holding a synthetic finger down for the system's long-press threshold. The
 * catch is picking the right view: the point lands inside every ancestor too,
 * so "contains the point" alone would just as happily pick the whole window.
 */
class LongClickTargetTest {

    private fun candidate(
        index: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        longClickable: Boolean = true,
        depth: Int = 0
    ) = NodeCandidate(index, left, top, right, bottom, longClickable, depth)

    @Test
    fun `picks a long-clickable view under the point`() {
        val target = LongClickTarget.pick(
            listOf(candidate(index = 0, left = 0, top = 0, right = 100, bottom = 100)),
            x = 50,
            y = 50
        )

        assertEquals(0, target?.index)
    }

    @Test
    fun `ignores views the point is outside`() {
        val target = LongClickTarget.pick(
            listOf(candidate(index = 0, left = 0, top = 0, right = 10, bottom = 10)),
            x = 500,
            y = 500
        )

        assertNull(target)
    }

    @Test
    fun `ignores views that do not handle a long click`() {
        val target = LongClickTarget.pick(
            listOf(candidate(index = 0, left = 0, top = 0, right = 100, bottom = 100, longClickable = false)),
            x = 50,
            y = 50
        )

        assertNull(target)
    }

    @Test
    fun `prefers the innermost view when they are nested`() {
        // The point is inside the container as well as the item; long-clicking
        // the container would act on the wrong thing.
        val target = LongClickTarget.pick(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 1000, bottom = 1000, depth = 0),
                candidate(index = 1, left = 40, top = 40, right = 60, bottom = 60, depth = 5)
            ),
            x = 50,
            y = 50
        )

        assertEquals(1, target?.index)
    }

    @Test
    fun `prefers the smaller view when depth is equal`() {
        val target = LongClickTarget.pick(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 1000, bottom = 1000, depth = 3),
                candidate(index = 1, left = 40, top = 40, right = 60, bottom = 60, depth = 3)
            ),
            x = 50,
            y = 50
        )

        assertEquals(1, target?.index)
    }

    @Test
    fun `skips a deeper view that cannot be long-clicked in favour of one that can`() {
        // Very common: the innermost thing under the finger is a label, and the
        // row wrapping it is what actually handles the long press.
        val target = LongClickTarget.pick(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 1000, bottom = 100, depth = 2),
                candidate(index = 1, left = 40, top = 40, right = 60, bottom = 60, depth = 8, longClickable = false)
            ),
            x = 50,
            y = 50
        )

        assertEquals(0, target?.index)
    }

    @Test
    fun `nothing to pick from yields nothing`() {
        assertNull(LongClickTarget.pick(emptyList(), x = 50, y = 50))
    }

    @Test
    fun `a point exactly on the edge counts as inside`() {
        val target = LongClickTarget.pick(
            listOf(candidate(index = 0, left = 10, top = 10, right = 20, bottom = 20)),
            x = 10,
            y = 10
        )

        assertEquals(0, target?.index)
    }

    @Test
    fun `an empty view is never picked`() {
        // Collapsed or unmeasured views report zero-area bounds; long-clicking
        // one does nothing useful and would shadow a real target.
        val target = LongClickTarget.pick(
            listOf(
                candidate(index = 0, left = 50, top = 50, right = 50, bottom = 50, depth = 9),
                candidate(index = 1, left = 0, top = 0, right = 100, bottom = 100, depth = 1)
            ),
            x = 50,
            y = 50
        )

        assertEquals(1, target?.index)
    }
}
