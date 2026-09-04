package com.pocketds.kbm.ime

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.pocketds.kbm.debug.DebugLog
import com.pocketds.kbm.layout.InputMode
import com.pocketds.kbm.settings.PanelPlacement
import com.pocketds.kbm.settings.ThemeSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the input panel directly on the PocketDS's secondary (bottom) display via
 * the public Presentation API, instead of relying on the system's default IME
 * placement — which puts the keyboard on whichever screen the focused field is on,
 * not necessarily the bottom one.
 *
 * Rather than being torn down and rebuilt, the single window is resized and moved
 * between three shapes:
 *
 *  * [PanelState.EXPANDED] — the full keyboard/trackpad panel.
 *  * [PanelState.BUBBLE] — a small draggable circle. The window really is only
 *    that big, so the rest of the bottom screen belongs to whatever else is
 *    there; touches outside the bubble fall straight through to it.
 *  * [PanelState.TUCKED] — shoved off the side, leaving a sliver to grab. The
 *    sliver drags along the edge too, so it can be moved off anything important.
 *
 * BottomPanelService decides *whether this exists at all* (only while PocketDS
 * Keyboard is the selected IME); everything here is about which shape it's in.
 */
class BottomScreenPresentation(
    context: Context,
    display: Display,
    private val keyboardListener: FullKeyboardListener,
    private val trackpadListener: TrackpadPanel.Listener,
    private val onSettingsClick: (() -> Unit)? = null,
    private val onOnePasswordClick: (() -> Unit)? = null,
    private val onModeChanged: ((InputMode) -> Unit)? = null,
    private val onHideRequested: (() -> Unit)? = null,
    private val onTemporaryCollapse: (() -> Unit)? = null,
    private val onExpandRequested: (() -> Unit)? = null
) : Presentation(context, display), BubbleView.Host {

    enum class PanelState { EXPANDED, BUBBLE, TUCKED }

    private lateinit var bubble: BubbleView
    private lateinit var inputPanelView: InputPanelView

    private var state = PanelState.BUBBLE
    /** Window position of the bubble, in pixels from the display's top-left. */
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var onLeft = true

    private var dragStartX = 0f
    private var dragStartY = 0f

    companion object {
        private const val BUBBLE_SIZE_DP = 52
        /** Invisible margin around the disc: a bigger touch target than the
         * drawn circle, and room for the press animation to grow into. */
        private const val BUBBLE_PAD_DP = 9
        private const val TUCK_WIDTH_DP = 14
        private const val TUCK_HEIGHT_DP = 46
        /**
         * Kept well clear of the screen edge on purpose. Ayaneo's own system
         * gestures own the outermost few pixels — swiping from there closes or
         * switches apps — so parking the bubble flush against it made grabbing
         * the bubble fight the system. This doesn't cure that (the edge isn't
         * ours), but it means an ordinary grab starts inside our target rather
         * than in the system's gesture zone.
         */
        private const val EDGE_MARGIN_DP = 14
        /** The tucked grip stays slightly off the edge for the same reason. */
        private const val TUCK_INSET_DP = 5
        /** Past this, a release is treated as a throw in that direction rather
         * than a drop at that position. */
        private const val FLING_VELOCITY = 900f
        /** How far the bubble has to be pushed past the edge on release before
         * it parks as a sliver instead of springing back into view. */
        private const val TUCK_OVERSHOOT_FRACTION = 0.35f
        /** Pulling a tucked sliver this far inward brings the bubble back out
         * mid-drag, so it can be rescued in one motion. */
        private const val UNTUCK_PULL_DP = 20
    }

    private val density get() = context.resources.displayMetrics.density
    private val screenWidth get() = context.resources.displayMetrics.widthPixels
    private val screenHeight get() = context.resources.displayMetrics.heightPixels
    private val padPx get() = BUBBLE_PAD_DP * density
    /** Window size, i.e. the drawn disc plus its invisible margin. */
    private val bubbleWindowPx get() = ((BUBBLE_SIZE_DP + 2 * BUBBLE_PAD_DP) * density).toInt()
    private val tuckWindowWidthPx get() = ((TUCK_WIDTH_DP + 2 * BUBBLE_PAD_DP) * density).toInt()
    private val tuckWindowHeightPx get() = ((TUCK_HEIGHT_DP + 2 * BUBBLE_PAD_DP) * density).toInt()
    private val edgeMarginPx get() = EDGE_MARGIN_DP * density
    private val tuckInsetPx get() = TUCK_INSET_DP * density

    // Damping and stiffness are the two knobs that decide whether this feels
    // right: 0.75 settles with a hint of overshoot rather than either bouncing
    // around or arriving dead. Both are worth re-tuning on the device.
    private val xSpring = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce()
            .setDampingRatio(0.75f)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
        addUpdateListener { _, value, _ ->
            bubbleX = value
            applyWindowGeometry(sizeChanged = false)
        }
    }
    private val ySpring = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce()
            .setDampingRatio(0.75f)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
        addUpdateListener { _, value, _ ->
            bubbleY = value
            applyWindowGeometry(sizeChanged = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same flag the real system keyboard uses: touchable, but never takes window
        // focus, so tapping it doesn't end the input session on the focused field's
        // window (on the other display).
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // A Presentation is a Dialog underneath, and a dialog window paints its
        // own opaque background. While the window filled the screen the panel
        // covered it, but a bubble-sized window left that background showing as
        // an ugly white square around the disc. Clearing it means only what we
        // actually draw is visible — while the window (and so the touch target)
        // stays the full padded square.
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Monochrome on purpose: a small disc that sits quietly over whatever is
        // behind it reads better than a shrunken colour icon. Dark disc with a
        // light glyph on a dark theme, and the reverse on a light one.
        val dark = isDarkTheme()
        bubble = BubbleView(
            context,
            padPx = padPx,
            fillColor = if (dark) 0xE61C1C1E.toInt() else 0xF2FFFFFF.toInt(),
            glyphColor = if (dark) 0xFFF2F2F2.toInt() else 0xFF1C1C1E.toInt(),
            host = this
        )
        inputPanelView = InputPanelView(
            context, keyboardListener, trackpadListener,
            onSettingsClick, onOnePasswordClick, onModeChanged,
            onHideClick = {
                setExpanded(false)
                onHideRequested?.invoke()
            },
            onCollapseForPicker = {
                setExpanded(false)
                onTemporaryCollapse?.invoke()
            }
        )

        val root = FrameLayout(context).apply {
            addView(
                bubble,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                inputPanelView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)

        restorePlacement()

        // getInsetsController()/decorView need the window actually attached, which
        // hasn't happened yet this early in onCreate (Presentation.show() attaches it
        // after onCreate returns) — calling this synchronously here crashes with an NPE
        // deep in PhoneWindow. post() defers it until the view hierarchy is attached.
        window?.decorView?.post { hideSystemBars() }
        applyState()
    }

    private fun isDarkTheme(): Boolean = when (ThemeSettings.getMode(context)) {
        ThemeSettings.Mode.DARK -> true
        ThemeSettings.Mode.LIGHT -> false
        ThemeSettings.Mode.SYSTEM ->
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun restorePlacement() {
        onLeft = PanelPlacement.isOnLeft(context)
        bubbleY = PanelPlacement.verticalFraction(context) * screenHeight
        state = if (PanelPlacement.isTucked(context)) PanelState.TUCKED else PanelState.BUBBLE
        bubbleX = restingX()
    }

    private fun savePlacement() {
        PanelPlacement.save(
            context,
            onLeft = onLeft,
            verticalFraction = if (screenHeight > 0) bubbleY / screenHeight else 0.35f,
            tucked = state == PanelState.TUCKED
        )
    }

    fun isExpanded() = state == PanelState.EXPANDED

    /**
     * Collapsing returns to whichever parked shape the user last chose, so
     * tucking it away out of the way isn't undone by the next focus change.
     */
    fun setExpanded(expand: Boolean) {
        val target = when {
            expand -> PanelState.EXPANDED
            PanelPlacement.isTucked(context) -> PanelState.TUCKED
            else -> PanelState.BUBBLE
        }
        if (state == target) return
        state = target
        cancelSprings()
        if (!expand) bubbleX = restingX()
        applyState()
    }

    // --- BubbleView.Host -----------------------------------------------------

    override fun onBubbleTapped() {
        when (state) {
            // A tucked sliver only comes back out as far as the bubble: pulling it
            // into view and opening the whole keyboard are separate intentions.
            PanelState.TUCKED -> {
                DebugLog.log("panel", "bubble pulled back out of the edge")
                state = PanelState.BUBBLE
                savePlacement()
                cancelSprings()
                applyState()
                springTo(xSpring, bubbleX, restingX(), 0f)
            }
            PanelState.BUBBLE -> {
                DebugLog.log("panel", "bubble tapped, expanding")
                state = PanelState.EXPANDED
                applyState()
                onExpandRequested?.invoke()
            }
            PanelState.EXPANDED -> Unit
        }
    }

    override fun onBubbleDragStarted() {
        cancelSprings()
        dragStartX = bubbleX
        dragStartY = bubbleY
    }

    override fun onBubbleDragged(totalDx: Float, totalDy: Float) {
        // Horizontal overshoot past the edge is allowed and left unclamped: how
        // far past it ends up is exactly what decides tuck-versus-snap on release.
        bubbleX = dragStartX + totalDx
        bubbleY = clamp(dragStartY + totalDy, 0f, (screenHeight - currentHeightPx()).toFloat())

        // Dragging a sliver inward pops the bubble back out mid-gesture, so it
        // can be rescued and repositioned in a single motion.
        if (state == PanelState.TUCKED) {
            val pulledInward = if (onLeft) totalDx > UNTUCK_PULL_DP * density
            else totalDx < -UNTUCK_PULL_DP * density
            if (pulledInward) {
                state = PanelState.BUBBLE
                applyState()
            }
        }
        applyWindowGeometry(sizeChanged = false)
    }

    override fun onBubbleDragEnded(velocityX: Float, velocityY: Float) {
        val center = bubbleX + bubbleWindowPx / 2f
        // Thrown hard beats where it happens to have landed, so a flick works
        // from anywhere rather than only near an edge.
        onLeft = when {
            abs(velocityX) > FLING_VELOCITY -> velocityX < 0
            else -> center < screenWidth / 2f
        }

        val pushedOffLeft = bubbleX < -bubbleWindowPx * TUCK_OVERSHOOT_FRACTION
        val pushedOffRight = bubbleX > screenWidth - bubbleWindowPx * (1f - TUCK_OVERSHOOT_FRACTION)
        val shouldTuck = (pushedOffLeft && onLeft) || (pushedOffRight && !onLeft)

        state = if (shouldTuck) PanelState.TUCKED else PanelState.BUBBLE
        DebugLog.log(
            "panel",
            "bubble released -> ${if (shouldTuck) "tucked" else "parked"} on ${if (onLeft) "left" else "right"}"
        )
        applyState()

        bubbleY = clamp(bubbleY, 0f, (screenHeight - currentHeightPx()).toFloat())
        springTo(xSpring, bubbleX, restingX(), velocityX)
        springTo(ySpring, bubbleY, bubbleY, velocityY)
        savePlacement()
    }

    // --- Geometry ------------------------------------------------------------

    /**
     * Where the bubble or grip settles once it's done moving.
     *
     * Deliberately never negative: window x/y offsets get clamped to the display
     * by the window manager, so trying to hang a bubble-width window off the
     * edge just produced a full-size square sitting at x=0 — which is what made
     * the "tucked" state look identical to the untucked one. The grip is a
     * genuinely smaller window instead.
     */
    private fun restingX(): Float = when (state) {
        PanelState.TUCKED ->
            if (onLeft) tuckInsetPx - padPx
            else screenWidth - tuckWindowWidthPx + padPx - tuckInsetPx
        else ->
            if (onLeft) edgeMarginPx - padPx
            else screenWidth - bubbleWindowPx + padPx - edgeMarginPx
    }

    private fun currentHeightPx(): Int = when (state) {
        PanelState.EXPANDED -> screenHeight
        PanelState.BUBBLE -> bubbleWindowPx
        PanelState.TUCKED -> tuckWindowHeightPx
    }

    private fun applyState() {
        if (!::bubble.isInitialized) return
        val expanded = state == PanelState.EXPANDED
        inputPanelView.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble.visibility = if (expanded) View.GONE else View.VISIBLE
        if (!expanded) bubble.setTucked(state == PanelState.TUCKED)
        applyWindowGeometry(sizeChanged = true)
        bubble.post {
            DebugLog.log(
                "panel",
                "state=$state bubble=${bubble.width}x${bubble.height} shown=${bubble.isShown} " +
                    "decor=${window?.decorView?.width}x${window?.decorView?.height}"
            )
        }
    }

    private fun applyWindowGeometry(sizeChanged: Boolean) {
        val win = window ?: return
        val params = win.attributes
        params.gravity = Gravity.TOP or Gravity.START
        when (state) {
            PanelState.EXPANDED -> {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
            }
            PanelState.BUBBLE -> {
                params.width = bubbleWindowPx
                params.height = bubbleWindowPx
                params.x = bubbleX.toInt()
                params.y = bubbleY.toInt()
            }
            PanelState.TUCKED -> {
                params.width = tuckWindowWidthPx
                params.height = tuckWindowHeightPx
                params.x = bubbleX.toInt()
                params.y = bubbleY.toInt()
            }
        }
        win.attributes = params

        val decor = win.decorView
        if (decor.isAttachedToWindow) {
            forceRelayout(decor, params, sizeChanged)
        } else if (sizeChanged) {
            // Right after show() the decor view isn't attached yet (attach happens
            // on the next traversal), and the service calls setExpanded()
            // immediately after show() — so doing this synchronously would skip
            // the relayout entirely for a panel that comes up already expanded.
            // That's exactly the "freshly-shown Trackpad ignores touches until you
            // switch tabs" case, so it has to be deferred rather than dropped.
            decor.post {
                val w = window ?: return@post
                if (w.decorView.isAttachedToWindow) forceRelayout(w.decorView, w.attributes, true)
            }
        }
    }

    /**
     * Setting window attributes alone updates the stored LayoutParams but on this
     * device doesn't reliably relayout an already-shown Presentation — the
     * system's "Requested w/h" just stays put, so the window keeps its old size
     * and child views keep stale measurements (a panel measured while collapsed
     * ends up with zero-height touch targets). Pushing the same params through
     * updateViewLayout() forces it.
     *
     * requestLayout() is only worth it when the size actually changed; during a
     * drag the size is constant and only the position moves, and re-measuring the
     * whole hierarchy every frame would just cost jank.
     */
    private fun forceRelayout(decor: View, params: WindowManager.LayoutParams, sizeChanged: Boolean) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.updateViewLayout(decor, params)
        if (sizeChanged) decor.requestLayout()
    }

    private fun springTo(spring: SpringAnimation, from: Float, to: Float, velocity: Float) {
        spring.cancel()
        spring.setStartValue(from)
        spring.setStartVelocity(velocity)
        spring.animateToFinalPosition(to)
    }

    private fun cancelSprings() {
        xSpring.cancel()
        ySpring.cancel()
    }

    private fun clamp(value: Float, minVal: Float, maxVal: Float) = max(minVal, min(maxVal, value))

    /**
     * Best-effort attempt at hiding whatever system bar/strip Android (or Ayaneo's
     * launcher) draws on this display.
     */
    private fun hideSystemBars() {
        val win = window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false)
            win.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
