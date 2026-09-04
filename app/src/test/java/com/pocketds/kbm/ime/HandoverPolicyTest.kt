package com.pocketds.kbm.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Handing the bottom screen to another app.
 *
 * Opening 1Password there put it *underneath* our own panel, which covers the
 * whole screen — so the button looked broken while 1Password sat behind it. The
 * panel has to get out of the way, and then stay out of the way: 1Password
 * starts its own input session the moment it appears, and that is precisely the
 * event that otherwise brings the panel back up over it.
 *
 * Staying out of the way cannot be permanent either, or the next field tapped
 * on the top screen would come up with no keyboard.
 */
class HandoverPolicyTest {

    @Test
    fun `with nothing handed over the panel expands`() {
        assertEquals(
            HandoverDecision.EXPAND,
            HandoverPolicy.decide(handedOverTo = null, editorPackage = "com.android.chrome")
        )
    }

    @Test
    fun `the app we handed the screen to does not get covered up`() {
        // The regression this exists to prevent: 1Password's own field starts a
        // session, the panel expands, and it is invisible again.
        assertEquals(
            HandoverDecision.SUPPRESS,
            HandoverPolicy.decide(
                handedOverTo = "com.onepassword.android",
                editorPackage = "com.onepassword.android"
            )
        )
    }

    @Test
    fun `focusing a field in another app takes the screen back`() {
        // Tapping a login box in Chrome on the top screen is an unambiguous
        // request for the keyboard, and it ends the handover.
        assertEquals(
            HandoverDecision.RELEASE_AND_EXPAND,
            HandoverPolicy.decide(
                handedOverTo = "com.onepassword.android",
                editorPackage = "com.android.chrome"
            )
        )
    }

    @Test
    fun `an unnamed editor leaves the handed-over app alone`() {
        // EditorInfo does not always name a package. Guessing wrong in this
        // direction merely leaves the bubble up, which the user can tap;
        // guessing wrong the other way covers the app they just opened.
        assertEquals(
            HandoverDecision.SUPPRESS,
            HandoverPolicy.decide(handedOverTo = "com.onepassword.android", editorPackage = null)
        )
    }

    @Test
    fun `an unnamed editor with nothing handed over still expands`() {
        assertEquals(
            HandoverDecision.EXPAND,
            HandoverPolicy.decide(handedOverTo = null, editorPackage = null)
        )
    }
}
