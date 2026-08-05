package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.pocketds.kbm.ui.Theme
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An isometric "nub" like a laptop TrackPoint: push away from center to drive
 * continuous cursor movement — speed ramps up non-linearly the further you push
 * (small pushes stay slow/precise, pushing further gets disproportionately faster),
 * not how far you've dragged in total. Releasing springs the knob back. A tap
 * without pushing is a left click.
 */
class NubPanel(context: Context, private val listener: CursorListener) : FrameLayout(context) {

    companion object {
        private const val MAX_RADIUS_DP = 20f
        private const val TICK_MS = 12L
        private const val MAX_SPEED_PX_PER_TICK = 34f
        private const val TAP_SLOP_PX = 10f
        private const val SPRING_BACK_MS = 180L
    }

    private val colors = Theme.colors(context)
    private val accentColor = colors.accent

    private val knob = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val maxRadiusPx = MAX_RADIUS_DP * resources.displayMetrics.density

    private var displacementX = 0f
    private var displacementY = 0f
    private var downX = 0f
    private var downY = 0f
    private var totalMoved = 0f
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            val magnitude = sqrt(displacementX * displacementX + displacementY * displacementY)
            if (magnitude > 0f) {
                // Quadratic ease-in: near the center stays slow/precise, but speed
                // ramps up fast as you push toward the edge — a push near max radius
                // is disproportionately faster than half that push, not linear.
                val ratio = min(magnitude / maxRadiusPx, 1f)
                val speed = ratio * ratio * MAX_SPEED_PX_PER_TICK
                val scale = speed / magnitude
                listener.onMove(displacementX * scale, displacementY * scale)
            }
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        // No outer boundary ring — just the floating knob, per feedback that the
        // circle read as visual clutter rather than a useful affordance.
        val knobSize = (maxRadiusPx * 0.9f).toInt()
        addView(knob, LayoutParams(knobSize, knobSize, android.view.Gravity.CENTER))

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    knob.animate().cancel()
                    downX = event.x
                    downY = event.y
                    totalMoved = 0f
                    displacementX = 0f
                    displacementY = 0f
                    ticking = true
                    handler.post(tick)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val magnitude = sqrt(dx * dx + dy * dy)
                    totalMoved = magnitude
                    if (magnitude > 0f) {
                        val clampedMagnitude = min(magnitude, maxRadiusPx)
                        displacementX = dx / magnitude * clampedMagnitude
                        displacementY = dy / magnitude * clampedMagnitude
                        knob.translationX = displacementX
                        knob.translationY = displacementY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ticking = false
                    displacementX = 0f
                    displacementY = 0f
                    knob.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setInterpolator(OvershootInterpolator())
                        .setDuration(SPRING_BACK_MS)
                        .start()
                    if (totalMoved < TAP_SLOP_PX) listener.onLeftClick()
                    true
                }
                else -> false
            }
        }
    }
}
