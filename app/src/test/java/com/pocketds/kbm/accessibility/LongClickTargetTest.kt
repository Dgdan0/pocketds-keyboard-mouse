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
        clickable: Boolean = false,
        editable: Boolean = false,
        depth: Int = 0
    ) = NodeCandidate(index, left, top, right, bottom, longClickable, clickable, editable, depth)

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

    // --- how wide a net to cast ------------------------------------------

    @Test
    fun `a view that only advertises a plain click is still worth asking`() {
        // isLongClickable is advertising, not truth: plenty of views handle a
        // long press without setting it. Asking costs nothing, because the
        // action reports back whether it was handled.
        val target = LongClickTarget.pick(
            listOf(candidate(index = 0, left = 0, top = 0, right = 100, bottom = 100,
                longClickable = false, clickable = true)),
            x = 50,
            y = 50
        )

        assertEquals(0, target?.index)
    }

    @Test
    fun `a view that advertises a long click is asked before one that does not`() {
        val order = LongClickTarget.rank(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 100, bottom = 100,
                    longClickable = false, clickable = true, depth = 4),
                candidate(index = 1, left = 0, top = 0, right = 100, bottom = 100,
                    longClickable = true, depth = 4)
            ),
            x = 50,
            y = 50
        )

        assertEquals(listOf(1, 0), order.map { it.index })
    }

    @Test
    fun `candidates come back innermost first, so each can be tried in turn`() {
        val order = LongClickTarget.rank(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 100, bottom = 100, depth = 1),
                candidate(index = 1, left = 20, top = 20, right = 80, bottom = 80, depth = 7),
                candidate(index = 2, left = 10, top = 10, right = 90, bottom = 90, depth = 4)
            ),
            x = 50,
            y = 50
        )

        assertEquals(listOf(1, 2, 0), order.map { it.index })
    }

    @Test
    fun `a view too large to be a single target is left to the held press`() {
        // A long click carries no coordinates: it acts on a view, not a point.
        // For a small widget that is the same thing, but asking a web page or a
        // whole list to long-click itself acts in the wrong place — or does
        // nothing while reporting success, which is worse, since it would stop
        // us falling back. A held finger is slower but lands where the cursor
        // actually is, so anything container-sized belongs to it.
        val order = LongClickTarget.rank(
            listOf(candidate(index = 0, left = 0, top = 0, right = 1000, bottom = 1000)),
            x = 500,
            y = 500,
            maxArea = 100 * 100
        )

        assertEquals(emptyList<Int>(), order.map { it.index })
    }

    @Test
    fun `a widget inside a view too large to target is still offered`() {
        val order = LongClickTarget.rank(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 1000, bottom = 1000, depth = 1),
                candidate(index = 1, left = 480, top = 480, right = 520, bottom = 520, depth = 6)
            ),
            x = 500,
            y = 500,
            maxArea = 100 * 100
        )

        assertEquals(listOf(1), order.map { it.index })
    }

    // --- text is a special case ------------------------------------------

    @Test
    fun `a text field is not treated as an ordinary target`() {
        // A long click names a view, not a point, and for text the point is the
        // whole question: which word. Asking a field to long-click itself
        // selects wherever its caret happens to be, which is not where the
        // cursor is pointing, and it reports success — so it would also stop
        // anything better being tried.
        val order = LongClickTarget.rank(
            listOf(candidate(index = 0, left = 0, top = 0, right = 400, bottom = 60, editable = true)),
            x = 200,
            y = 30
        )

        assertEquals(emptyList<Int>(), order.map { it.index })
    }

    @Test
    fun `the text field under the cursor is offered separately`() {
        val field = LongClickTarget.textFieldAt(
            listOf(candidate(index = 0, left = 0, top = 0, right = 400, bottom = 60, editable = true)),
            x = 200,
            y = 30
        )

        assertEquals(0, field?.index)
    }

    @Test
    fun `a text field the cursor is not over is not offered`() {
        val field = LongClickTarget.textFieldAt(
            listOf(candidate(index = 0, left = 0, top = 0, right = 400, bottom = 60, editable = true)),
            x = 200,
            y = 500
        )

        assertNull(field)
    }

    @Test
    fun `the innermost text field wins`() {
        val field = LongClickTarget.textFieldAt(
            listOf(
                candidate(index = 0, left = 0, top = 0, right = 400, bottom = 200, editable = true, depth = 2),
                candidate(index = 1, left = 10, top = 10, right = 390, bottom = 60, editable = true, depth = 9)
            ),
            x = 200,
            y = 30
        )

        assertEquals(1, field?.index)
    }

    @Test
    fun `a button is not mistaken for a text field`() {
        val field = LongClickTarget.textFieldAt(
            listOf(candidate(index = 0, left = 0, top = 0, right = 400, bottom = 60)),
            x = 200,
            y = 30
        )

        assertNull(field)
    }
}
