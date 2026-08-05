package com.pocketds.kbm.ime

/** Shared by anything that drives the mouse cursor: the trackpad and the nub. */
interface CursorListener {
    fun onMove(dx: Float, dy: Float)
    fun onLeftClick()
    fun onRightClick()
}
