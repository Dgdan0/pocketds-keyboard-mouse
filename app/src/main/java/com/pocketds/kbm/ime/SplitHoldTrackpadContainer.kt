package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.pocketds.kbm.ui.Theme
import kotlin.math.abs

/**
 * Holds the keyboard normally, but a sustained hold anywhere on it fades it out and
 * turns the whole area into a full trackpad — for cases where Split's small dedicated
 * trackpad strip up top isn't enough room. Releasing starts a cooldown: touching again
 * within that window keeps it as a trackpad, letting it run out fades back to the
 * keyboard.
 */
class SplitHoldTrackpadContainer(
    context: Context,
    keyboardListener: KeyboardPanel.Listener,
    private val trackpadListener: TrackpadPanel.Listener
) : FrameLayout(context) {

    private enum class State { NORMAL, HOLD_PENDING, TRACKPAD_ACTIVE }

    companion object {
        private const val HOLD_THRESHOLD_MS = 650L
        private const val COOLDOWN_MS = 2000L
        private const val MOVE_SLOP_PX = 20f
        private const val FADE_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.NORMAL
    private var downX = 0f
    private var downY = 0f

    private val touchAdapter = TrackpadTouchAdapter(trackpadListener)

    private val keyboardPanel = KeyboardPanel(context, keyboardListener)
    private val trackpadOverlay = TextView(context).apply {
        val colors = Theme.colors(context)
        text = "Trackpad (release ${COOLDOWN_MS / 1000}s to return to keyboard)"
        gravity = Gravity.CENTER
        setTextColor(colors.mutedText)
        setBackgroundColor(colors.keySurface)
        alpha = 0f
        visibility = View.GONE
    }

    private val holdRunnable = Runnable { enterTrackpadMode() }
    private val cooldownRunnable = Runnable { exitTrackpadMode() }

    init {
        addView(keyboardPanel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(trackpadOverlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                if (state == State.TRACKPAD_ACTIVE) {
                    handler.removeCallbacks(cooldownRunnable)
                    return true
                }
                if (isOnBackspace(ev.x, ev.y)) return false
                state = State.HOLD_PENDING
                handler.postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == State.HOLD_PENDING) {
                    val moved = abs(ev.x - downX) + abs(ev.y - downY)
                    if (moved > MOVE_SLOP_PX) {
                        handler.removeCallbacks(holdRunnable)
                        state = State.NORMAL
                    }
                }
                return state == State.TRACKPAD_ACTIVE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == State.HOLD_PENDING) {
                    handler.removeCallbacks(holdRunnable)
                    state = State.NORMAL
                }
                return false
            }
        }
        return false
    }

    /**
     * Gesture recognition is delegated to the shared adapter, so the revealed
     * trackpad behaves exactly like the real one. It used to hold its own copy
     * of the state machine that only ever tracked a single pointer, which is why
     * two-finger scrolling silently did nothing here while working on Split's
     * strip up top.
     *
     * Note this can receive a MOVE with no DOWN before it: the hold gesture
     * starts intercepting part-way through a touch. The recogniser anchors
     * quietly in that case rather than reporting the position as travel.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchAdapter.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                handler.postDelayed(cooldownRunnable, COOLDOWN_MS)
        }
        return true
    }

    /** Backspace has its own hold behavior (repeat-delete) — don't let the container's
     * hold-to-reveal-trackpad gesture steal that touch out from under it. */
    private fun isOnBackspace(x: Float, y: Float): Boolean {
        val backspace = keyboardPanel.backspaceButton ?: return false
        val containerLoc = IntArray(2)
        val backspaceLoc = IntArray(2)
        getLocationOnScreen(containerLoc)
        backspace.getLocationOnScreen(backspaceLoc)
        val rect = Rect(
            backspaceLoc[0] - containerLoc[0],
            backspaceLoc[1] - containerLoc[1],
            backspaceLoc[0] - containerLoc[0] + backspace.width,
            backspaceLoc[1] - containerLoc[1] + backspace.height
        )
        return rect.contains(x.toInt(), y.toInt())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        touchAdapter.cancel()
    }

    private fun enterTrackpadMode() {
        if (state != State.HOLD_PENDING) return
        state = State.TRACKPAD_ACTIVE
        parent?.requestDisallowInterceptTouchEvent(true)
        trackpadOverlay.visibility = View.VISIBLE
        trackpadOverlay.animate().alpha(1f).setDuration(FADE_MS).start()
        keyboardPanel.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (state == State.TRACKPAD_ACTIVE) keyboardPanel.visibility = View.GONE
        }.start()
    }

    private fun exitTrackpadMode() {
        state = State.NORMAL
        keyboardPanel.visibility = View.VISIBLE
        keyboardPanel.animate().alpha(1f).setDuration(FADE_MS).start()
        trackpadOverlay.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            trackpadOverlay.visibility = View.GONE
        }.start()
    }
}
