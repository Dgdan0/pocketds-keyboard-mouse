package com.pocketds.kbm.ime

import android.view.MotionEvent
import com.pocketds.kbm.gesture.GestureCommand
import com.pocketds.kbm.gesture.GestureConfig
import com.pocketds.kbm.gesture.NO_POINTER
import com.pocketds.kbm.gesture.Pointer
import com.pocketds.kbm.gesture.TouchAction
import com.pocketds.kbm.gesture.TouchSample
import com.pocketds.kbm.gesture.TrackpadGestureRecognizer

/**
 * The only place `MotionEvent` becomes gesture input, and the only place gesture
 * commands get carried out. Both trackpad surfaces go through it, so they cannot
 * drift apart the way the two hand-written copies did.
 *
 * Deliberately contains no decisions — it is a mechanical translation, because
 * it is the one piece that cannot be unit-tested (`MotionEvent` needs a device
 * or Robolectric). Anything it had to decide would be untested code, so all of
 * that lives in [TrackpadGestureRecognizer] instead.
 */
class TrackpadTouchAdapter(
    private val listener: TrackpadPanel.Listener,
    config: GestureConfig = GestureConfig.DEFAULT
) {
    private val recognizer = TrackpadGestureRecognizer(config)

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = toAction(event.actionMasked) ?: return false
        execute(recognizer.onTouch(toSample(event, action)))
        return true
    }

    /** The surface is going away mid-gesture. Releases anything still held —
     * otherwise a panel dismissed while scrolling leaves a synthetic finger
     * pressed on the other screen, which this device causes on its own. */
    fun cancel() = execute(recognizer.reset())

    private fun toAction(actionMasked: Int): TouchAction? = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> TouchAction.DOWN
        MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
        MotionEvent.ACTION_MOVE -> TouchAction.MOVE
        MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
        MotionEvent.ACTION_UP -> TouchAction.UP
        MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
        else -> null
    }

    private fun toSample(event: MotionEvent, action: TouchAction): TouchSample {
        val pointers = ArrayList<Pointer>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            pointers += Pointer(
                id = event.getPointerId(index),
                x = event.getX(index),
                y = event.getY(index)
            )
        }
        val actionPointerId = when (action) {
            TouchAction.MOVE, TouchAction.CANCEL -> NO_POINTER
            else -> event.getPointerId(event.actionIndex)
        }
        return TouchSample(action, pointers, actionPointerId, event.eventTime)
    }

    private fun execute(commands: List<GestureCommand>) {
        for (command in commands) {
            when (command) {
                is GestureCommand.MoveCursor -> listener.onMove(command.dx, command.dy)
                is GestureCommand.Scroll -> listener.onScroll(command.dx, command.dy)
                GestureCommand.ScrollEnd -> listener.onScrollEnd()
                GestureCommand.LeftClick -> listener.onLeftClick()
                GestureCommand.RightClick -> listener.onRightClick()
            }
        }
    }
}
