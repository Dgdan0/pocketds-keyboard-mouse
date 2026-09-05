package com.pocketds.kbm.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overflow menu behind the ⋮ button.
 *
 * The mode strip was running out of room — five tabs and five icons on a 5"
 * screen — and every icon added to it steals width from the tabs, which are
 * what actually get used.
 */
class PanelMenuTest {

    private fun ids(compact: Boolean) = PanelMenu.items(compact).map { it.id }

    @Test
    fun `every action is reachable`() {
        assertEquals(
            listOf(
                PanelMenuId.HIDE,
                PanelMenuId.TOGGLE_SIZE,
                PanelMenuId.SCREEN_OFF,
                PanelMenuId.ONE_PASSWORD,
                PanelMenuId.CHANGE_KEYBOARD,
                PanelMenuId.SETTINGS
            ),
            ids(compact = false)
        )
    }

    @Test
    fun `the size item offers the size you are not`() {
        val whenFull = PanelMenu.items(compact = false).first { it.id == PanelMenuId.TOGGLE_SIZE }
        val whenCompact = PanelMenu.items(compact = true).first { it.id == PanelMenuId.TOGGLE_SIZE }

        assertEquals("Half height", whenFull.label)
        assertEquals("Full height", whenCompact.label)
    }

    @Test
    fun `the menu is the same length either way`() {
        // Items must not appear and disappear between openings, or the one you
        // were reaching for moves under your finger.
        assertEquals(ids(compact = false).size, ids(compact = true).size)
    }

    @Test
    fun `hide comes first, being the one used most`() {
        assertEquals(PanelMenuId.HIDE, PanelMenu.items(compact = false).first().id)
    }

    @Test
    fun `every item has an icon and a label`() {
        for (compact in listOf(false, true)) {
            for (item in PanelMenu.items(compact)) {
                assertTrue("${item.id} needs an icon", item.icon.isNotBlank())
                assertTrue("${item.id} needs a label", item.label.isNotBlank())
            }
        }
    }

    @Test
    fun `no two items share an icon`() {
        // They are read at a glance at small size, so a repeated glyph would
        // make two rows indistinguishable.
        val icons = PanelMenu.items(compact = false).map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
    }
}
