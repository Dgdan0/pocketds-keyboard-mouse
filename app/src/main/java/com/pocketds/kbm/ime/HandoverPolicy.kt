package com.pocketds.kbm.ime

enum class HandoverDecision {
    /** Nothing is using the bottom screen; bring the panel up. */
    EXPAND,

    /** Leave the screen to the app we gave it to; the bubble stays available. */
    SUPPRESS,

    /** Focus moved elsewhere, so the handover is over and the panel comes back. */
    RELEASE_AND_EXPAND
}

/**
 * Whether the panel may cover the bottom screen right now.
 *
 * Our panel fills that screen, so anything we launch there lands behind it.
 * Opening 1Password looked like a no-op for exactly this reason. Collapsing to
 * the bubble is only half the fix: the app we just opened starts its own input
 * session, and a starting session is what expands the panel — so it would
 * immediately cover the thing it got out of the way for.
 *
 * The editor's package is what separates the two cases. A session belonging to
 * the app we handed the screen to must not expand the panel; a session belonging
 * to anything else means focus has moved on, and the panel is wanted again.
 * That way the handover ends by itself instead of stranding the user with a
 * focused field and no keyboard.
 */
object HandoverPolicy {

    fun decide(handedOverTo: String?, editorPackage: String?): HandoverDecision = when {
        handedOverTo == null -> HandoverDecision.EXPAND
        // EditorInfo does not always name a package. Erring towards leaving the
        // screen alone only costs a tap on the bubble, where erring the other
        // way hides the app the user just asked for.
        editorPackage == null -> HandoverDecision.SUPPRESS
        editorPackage == handedOverTo -> HandoverDecision.SUPPRESS
        else -> HandoverDecision.RELEASE_AND_EXPAND
    }
}
