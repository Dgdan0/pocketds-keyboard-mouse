package com.pocketds.kbm.ime

enum class OccupancyChange {
    /** Something else is using the bottom screen now; the panel should stand down. */
    TAKEN,

    /** The bottom screen is ours again. */
    RELEASED,

    /** Nothing has changed, so nothing should move. */
    UNCHANGED
}

/**
 * Notices when something else is using the bottom screen.
 *
 * The panel covers that screen completely, so anything running there is behind
 * it. That was handled for 1Password alone, because we launched it and knew;
 * an app opened from the launcher got sat on instead, with the panel unaware
 * anything was there.
 *
 * Being right about *when nothing has changed* matters as much as spotting the
 * change: window events fire constantly, and treating each one as news would
 * re-collapse the panel the instant the user pulled it up over the app on
 * purpose.
 */
object BottomScreenOccupancy {

    fun isOccupant(packageName: String?, ourPackage: String, homePackages: Set<String>): Boolean =
        packageName != null &&
            packageName != ourPackage &&
            // A home screen behind the panel is the resting state, not something
            // the user is trying to see. Both launchers count: this device runs
            // a second one for the bottom screen, and it is always there — read
            // as an app, it would suppress the keyboard permanently.
            packageName !in homePackages

    fun change(was: String?, now: String?): OccupancyChange = when {
        was == now -> OccupancyChange.UNCHANGED
        now != null -> OccupancyChange.TAKEN
        else -> OccupancyChange.RELEASED
    }
}
