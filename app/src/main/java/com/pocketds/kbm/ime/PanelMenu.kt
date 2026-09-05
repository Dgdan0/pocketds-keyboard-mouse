package com.pocketds.kbm.ime

enum class PanelMenuId {
    HIDE,
    TOGGLE_SIZE,
    SCREEN_OFF,
    ONE_PASSWORD,
    CHANGE_KEYBOARD,
    SETTINGS
}

data class PanelMenuItem(val id: PanelMenuId, val icon: String, val label: String)

/**
 * What sits behind the ⋮ button.
 *
 * The mode strip was running out of room: five tabs and five icons on a 5"
 * screen, where every icon added steals width from the tabs, which are what
 * actually get used.
 *
 * The list is deliberately the same length whichever state the panel is in —
 * items that come and go move the one you were reaching for out from under your
 * finger.
 */
object PanelMenu {

    fun items(compact: Boolean): List<PanelMenuItem> = listOf(
        // First, being the one reached for most.
        PanelMenuItem(PanelMenuId.HIDE, "⌄", "Hide keyboard"),
        PanelMenuItem(
            PanelMenuId.TOGGLE_SIZE,
            "↕",
            // Named for where it takes you, not where you are.
            if (compact) "Full height" else "Half height"
        ),
        PanelMenuItem(PanelMenuId.SCREEN_OFF, "■", "Screen off"),
        PanelMenuItem(PanelMenuId.ONE_PASSWORD, "🔑", "1Password"),
        PanelMenuItem(PanelMenuId.CHANGE_KEYBOARD, "⌨", "Change keyboard"),
        PanelMenuItem(PanelMenuId.SETTINGS, "⚙", "Settings")
    )
}
