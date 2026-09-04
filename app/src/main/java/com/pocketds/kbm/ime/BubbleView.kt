package com.pocketds.kbm.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The collapsed form of the panel: a small draggable disc with a keyboard glyph,
 * or — once shoved off to the side — a slim vertical grip hugging the edge.
 *
 * Drawn rather than assembled from an ImageView and a launcher icon: at this
 * size the launcher icon reads as clutter, while a flat monochrome glyph stays
 * legible and sits quietly over whatever's behind it.
 *
 * [padPx] is empty space kept around the visible disc. It widens the touch
 * target beyond what's drawn, and leaves room for the press animation to grow
 * into without being clipped by the window edge.
 *
 * Only recognises gestures; it doesn't move anything. The window is what moves,
 * and that belongs to BottomScreenPresentation, so movement is reported through
 * [Host] in screen coordinates — view-relative ones would chase themselves as
 * the window slides along under the finger.
 */
class BubbleView(
    context: Context,
    private val padPx: Float,
    private val fillColor: Int,
    private val glyphColor: Int,
    private val host: Host
) : View(context) {

    interface Host {
        fun onBubbleTapped()
        fun onBubbleDragStarted()
        /** Offsets from where the drag began, in screen pixels. */
        fun onBubbleDragged(totalDx: Float, totalDy: Float)
        fun onBubbleDragEnded(velocityX: Float, velocityY: Float)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glyphColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glyphColor }
    private val bounds = RectF()

    private var tucked = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    // Sampled by hand rather than with a VelocityTracker: the window moves under
    // the finger, which makes the tracker's own coordinate history unreliable.
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastMoveAt = 0L
    private var velocityX = 0f
    private var velocityY = 0f

    init {
        setWillNotDraw(false)
    }

    fun setTucked(tucked: Boolean) {
        if (this.tucked == tucked) return
        this.tucked = tucked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(padPx, padPx, width - padPx, height - padPx)
        if (bounds.isEmpty) return

        if (tucked) {
            // A plain rounded grip. A glyph here would only be squashed into
            // something unreadable at a dozen pixels wide.
            val radius = bounds.width() / 2f
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)
            return
        }

        val radius = min(bounds.width(), bounds.height()) / 2f
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, fillPaint)
        drawKeyboardGlyph(canvas, radius)
    }

    /** A keyboard outline with two rows of keys — the smallest shape that still
     * reads as "keyboard" rather than as a generic rounded box. */
    private fun drawKeyboardGlyph(canvas: Canvas, radius: Float) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = radius * 1.15f
        val h = radius * 0.78f
        val stroke = radius * 0.1f
        glyphPaint.strokeWidth = stroke

        val body = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(body, stroke * 1.6f, stroke * 1.6f, glyphPaint)

        // Two rows of keys, then a wider "spacebar" centred on a third.
        val keySize = stroke * 1.05f
        val columns = 4
        val gapX = body.width() / (columns + 1f)
        val rowY1 = cy - h * 0.16f
        val rowY2 = cy + h * 0.06f
        for (row in listOf(rowY1, rowY2)) {
            for (i in 1..columns) {
                canvas.drawCircle(body.left + gapX * i, row, keySize / 2f, glyphFillPaint)
            }
        }
        val spaceWidth = body.width() * 0.42f
        val spaceY = cy + h * 0.28f
        canvas.drawRoundRect(
            RectF(cx - spaceWidth / 2f, spaceY - keySize / 2.6f, cx + spaceWidth / 2f, spaceY + keySize / 2.6f),
            keySize, keySize, glyphFillPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                lastMoveAt = event.eventTime
                velocityX = 0f
                velocityY = 0f
                dragging = false
                setPressedLook(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!dragging && hypot(totalDx, totalDy) > touchSlop) {
                    dragging = true
                    host.onBubbleDragStarted()
                }
                if (dragging) {
                    val elapsed = (event.eventTime - lastMoveAt).coerceAtLeast(1L)
                    // Blended with the previous sample so one jittery frame right
                    // at release doesn't decide which way it flies.
                    val sampleVx = (event.rawX - lastRawX) / elapsed * 1000f
                    val sampleVy = (event.rawY - lastRawY) / elapsed * 1000f
                    velocityX = velocityX * 0.6f + sampleVx * 0.4f
                    velocityY = velocityY * 0.6f + sampleVy * 0.4f
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    lastMoveAt = event.eventTime
                    host.onBubbleDragged(totalDx, totalDy)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                setPressedLook(false)
                if (dragging) {
                    host.onBubbleDragEnded(velocityX, velocityY)
                } else if (abs(event.rawX - downRawX) <= touchSlop &&
                    abs(event.rawY - downRawY) <= touchSlop
                ) {
                    host.onBubbleTapped()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                setPressedLook(false)
                if (dragging) host.onBubbleDragEnded(0f, 0f)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Grows very slightly under the finger, which is what makes it feel picked
     * up rather than merely tracked. Room for this is why [padPx] exists. */
    private fun setPressedLook(pressed: Boolean) {
        animate().cancel()
        if (pressed) {
            animate().scaleX(1.12f).scaleY(1.12f).alpha(1f).setDuration(90L).start()
        } else {
            animate().scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(1.6f))
                .setDuration(220L)
                .start()
        }
    }
}
