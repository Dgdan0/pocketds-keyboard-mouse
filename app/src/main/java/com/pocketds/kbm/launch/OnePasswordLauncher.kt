package com.pocketds.kbm.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.pocketds.kbm.debug.DebugLog

/**
 * Opens 1Password on a chosen display, and says so in the debug trace whatever
 * happens.
 *
 * Shared by the panel's key icon and the button in Settings, which each had
 * their own copy with its own hardcoded package name.
 *
 * Every failure here used to be silent: the "not installed" branch logged with
 * `Log.w`, which never reaches the on-device trace, and `startActivity` was not
 * guarded at all — so a launch refused by the system (which happens for
 * non-default displays) looked identical to nothing being tapped.
 *
 * @return the package that was opened, or null if nothing was. The caller needs
 *   the name because launching onto the bottom screen means handing that screen
 *   over, and it has to know which app it handed it to.
 */
fun launchOnePasswordOnDisplay(context: Context, displayId: Int): String? {
    val packageManager = context.packageManager
    val target = OnePasswordPackages.resolve { candidate ->
        packageManager.getLaunchIntentForPackage(candidate) != null
    }

    if (target == null) {
        DebugLog.log(
            "launch",
            "1Password not found. Tried ${OnePasswordPackages.candidates.joinToString()} " +
                "- either it isn't installed, or it isn't declared in <queries>"
        )
        return null
    }

    val intent = packageManager.getLaunchIntentForPackage(target)
    if (intent == null) {
        DebugLog.log("launch", "$target vanished between resolving and launching")
        return null
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
    return try {
        context.startActivity(intent, options.toBundle())
        DebugLog.log("launch", "opened $target on display $displayId")
        target
    } catch (e: Exception) {
        // Launching onto a non-default display can be refused outright, and a
        // background launch can be blocked. Both used to fail invisibly.
        DebugLog.log("launch", "could not open $target: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
