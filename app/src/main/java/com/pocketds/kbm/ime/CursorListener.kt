package com.pocketds.kbm.ime

/** Shared by anything that drives the mouse cursor: the trackpad and the nub. */
interface CursorListener {
    fun onMove(dx: Float, dy: Float)
    fun onLeftClick()
    fun onRightClick()

    /**
     * Press and hold at the cursor, so that moving it drags out a text
     * selection rather than just sliding the pointer over the words.
     *
     * Defaulted, because the surfaces that only push the pointer around have no
     * use for it.
     */
    fun onDragStart() {}

    fun onDragEnd() {}
}
