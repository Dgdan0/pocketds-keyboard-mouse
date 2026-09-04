package com.pocketds.kbm.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.pocketds.kbm.debug.DebugLog
import com.pocketds.kbm.settings.CursorSettings
import com.pocketds.kbm.settings.ThemeSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CursorAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: View
    private lateinit var cursorParams: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0

    // --- Cursor visibility ---------------------------------------------------
    // "Allowed" is whether a cursor-driving mode is up at all (the panel decides
    // that); on top of it the cursor hides itself after a spell of no trackpad
    // use, and when the user reaches up and touches the top screen directly —
    // in both cases it's stopped being the thing they're pointing with, and a
    // stale arrow sitting on screen just looks like a smudge.
    private var cursorAllowed = false
    private var cursorShown = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val hideOnIdle = Runnable {
        if (cursorShown) {
            DebugLog.log("cursor", "hiding after idle timeout")
            setCursorShown(false)
        }
    }
    // When we dispatch a gesture ourselves it generates the same accessibility
    // events a real finger would, so real touches are told apart by "did we just
    // dispatch something?" rather than by anything in the event itself.
    private var lastSyntheticDispatchAt = 0L

    // --- Scroll --------------------------------------------------------------
    //
    // Dispatched as a ONE-finger drag, even though the user makes it with two
    // fingers on the trackpad. Those are different things: two fingers is how an
    // indirect pointing surface distinguishes "scroll" from "move the cursor",
    // whereas what lands on the top screen is a synthetic touchscreen gesture —
    // and a touchscreen scrolls with one finger. Two fingers there means
    // pinch-to-zoom, which is why the earlier two-finger version zoomed in comic
    // readers instead of turning pages, and why sideways scrolling was fragile.
    // One finger is what a real hand would do, so it also gets page swipes,
    // carousels and swipe-to-dismiss for free.
    //
    // It's one continuous held gesture — a touch-down, a chain of
    // continueStroke() extensions, then a lift — because discrete drag-and-lift
    // segments dispatch fine but feel visibly segmented (confirmed on hardware).
    // Three things make that fiddly:
    //
    //  * continueStroke() only works on a stroke that has actually been
    //    dispatched AND completed. Continuing one that was never itself
    //    dispatched is silently cancelled — that was the original bug here.
    //  * Only one gesture is in flight at a time, and a segment outlasts the
    //    gap between touch-move events, so dispatches queue up and a new flick
    //    can begin while the previous one is still winding down.
    //    scrollGeneration lets callbacks belonging to an abandoned session be
    //    discarded instead of clobbering the new session's state.
    //  * The synthetic finger travels across a real screen, so a long swipe runs
    //    out of room. On hitting the edge it's lifted and re-planted back at the
    //    anchor, carrying the leftover movement over, rather than clamping and
    //    silently dropping the rest of the swipe.
    //
    // pumpScroll() is the only place that decides what to dispatch next, so the
    // sequencing lives in one spot instead of being restated in every callback.
    private var scrollGeneration = 0
    private var scrollSessionActive = false
    private var scrollDispatchInFlight = false
    private var scrollFingerDown = false
    private var scrollNeedsReanchor = false
    private var scrollStroke: GestureDescription.StrokeDescription? = null
    private var pendingScrollDx = 0f
    private var pendingScrollDy = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    companion object {
        var instance: CursorAccessibilityService? = null
            private set

        private const val CURSOR_SIZE_DP = 26
        private const val TAP_DURATION_MS = 40L
        // Android has no native right-click gesture; a long-press is the closest
        // system-wide equivalent (it opens context menus the same way). This needs
        // real margin above ViewConfiguration's ~500ms long-press timeout, or our
        // gesture's "up" can land right at the wire and lose the race, silently
        // eating the long-press instead of triggering it.
        private const val LONG_PRESS_DURATION_MS = 650L
        private const val SCROLL_SEGMENT_DURATION_MS = 40L
        // The initial "fingers touch down" stroke — short, since nothing should
        // visibly happen until the first real movement extends it.
        private const val SCROLL_TOUCH_DOWN_MS = 20L
        // How far inside the screen edges the synthetic finger stays. Leaves room
        // to notice we've run out of travel and re-plant it mid-swipe.
        private const val SCROLL_EDGE_MARGIN_PX = 90f
        private const val FADE_DURATION_MS = 150L
        // How long after one of our own dispatched gestures an accessibility
        // event is still assumed to be the echo of it rather than a real finger.
        // Generous, because a scroll dispatches continuously and each segment's
        // events can land a little after the dispatch returns.
        private const val SYNTHETIC_ECHO_WINDOW_MS = 500L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLog.log("cursor", "accessibility service connected")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        val sizePx = (CURSOR_SIZE_DP * metrics.density).toInt()
        val (fill, outline) = cursorColors()
        cursorView = CursorPointerView(this, sizePx, fill, outline)

        cursorParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // The tip of the arrow (the view's top-left) is the hotspot, so it sits
            // exactly at (cursorX, cursorY) — that's what lets it reach true corners;
            // a centered icon always clips its body before the center hits the edge.
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }

        cursorView.visibility = View.GONE
        windowManager.addView(cursorView, cursorParams)
    }

    /**
     * Whether a cursor-driving mode (Trackpad/Nub/Split) is up at all — set by
     * the panel. While it isn't, the cursor stays hidden so it doesn't sit on
     * screen during plain typing with nothing to do. While it is, the cursor
     * still comes and goes on its own: see [onCursorActivity].
     */
    fun setCursorAllowed(allowed: Boolean) {
        cursorAllowed = allowed
        if (allowed) {
            onCursorActivity()
        } else {
            idleHandler.removeCallbacks(hideOnIdle)
            setCursorShown(false)
        }
    }

    /**
     * The trackpad was just used, so show the cursor and restart the idle
     * countdown. Called from every interaction rather than only from movement,
     * so the cursor doesn't time out mid-click or mid-scroll.
     */
    private fun onCursorActivity() {
        if (!cursorAllowed) return
        setCursorShown(true)
        idleHandler.removeCallbacks(hideOnIdle)
        val seconds = CursorSettings.idleHideSeconds(this)
        if (seconds != CursorSettings.IDLE_NEVER) {
            idleHandler.postDelayed(hideOnIdle, seconds * 1000L)
        }
    }

    /** Fades rather than snapping, since an instant appear/disappear read as jarring. */
    private fun setCursorShown(shown: Boolean) {
        if (!::cursorView.isInitialized || cursorShown == shown) return
        cursorShown = shown
        cursorView.animate().cancel()
        if (shown) {
            cursorView.alpha = 0f
            cursorView.visibility = View.VISIBLE
            cursorView.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        } else {
            cursorView.animate().alpha(0f).setDuration(FADE_DURATION_MS)
                .withEndAction { cursorView.visibility = View.GONE }
                .start()
        }
    }

    /** Re-reads the theme and recolours the pointer in place. */
    fun refreshTheme() {
        if (!::cursorView.isInitialized) return
        val (fill, outline) = cursorColors()
        (cursorView as? CursorPointerView)?.setColors(fill, outline)
    }

    /**
     * White with a black outline in light mode — the shape everyone already
     * reads as a mouse pointer, and legible against pale app backgrounds where
     * the accent colour washed out. Dark mode keeps the accent, which reads
     * clearly there and makes it obvious which pointer is ours.
     */
    private fun cursorColors(): Pair<Int, Int> {
        val dark = when (ThemeSettings.getMode(this)) {
            ThemeSettings.Mode.DARK -> true
            ThemeSettings.Mode.LIGHT -> false
            ThemeSettings.Mode.SYSTEM ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) {
            0xFF2BE0CE.toInt() to 0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        }
    }

    fun moveCursorBy(dx: Float, dy: Float) {
        onCursorActivity()
        cursorX = clamp(cursorX + dx, 0f, screenWidth.toFloat())
        cursorY = clamp(cursorY + dy, 0f, screenHeight.toFloat())
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        windowManager.updateViewLayout(cursorView, cursorParams)
    }

    fun click() {
        onCursorActivity()
        tapAt(cursorX, cursorY, TAP_DURATION_MS)
    }

    fun rightClick() {
        onCursorActivity()
        tapAt(cursorX, cursorY, LONG_PRESS_DURATION_MS)
    }

    /**
     * Feeds movement into a drag anchored on the cursor — the only scroll
     * mechanism AccessibilityService's gesture API exposes, since there's no
     * mouse-wheel/AXIS_VSCROLL injection available to accessibility services.
     * dx/dy are already sign-adjusted by the caller for the invert-scroll setting.
     */
    fun scrollBy(dx: Float, dy: Float) {
        onCursorActivity()
        if (!scrollSessionActive) beginScrollSession()
        pendingScrollDx += dx
        pendingScrollDy += dy
        pumpScroll()
    }

    /** The real fingers left the trackpad: lift the synthetic one, so the next
     * scrollBy() starts a fresh drag anchored back on the cursor. */
    fun endScroll() {
        if (!scrollSessionActive) return
        scrollSessionActive = false
        DebugLog.log("scroll", "session end")
        pumpScroll()
    }

    private fun beginScrollSession() {
        // Bump the generation first: anything still in flight from the previous
        // drag belongs to a dead session now, and its callbacks must not touch
        // the state being set up here.
        scrollGeneration++
        scrollSessionActive = true
        scrollFingerDown = false
        scrollNeedsReanchor = false
        scrollStroke = null
        // Deltas left over from a previous or cancelled drag would otherwise be
        // replayed into this one as a single amplified jump.
        pendingScrollDx = 0f
        pendingScrollDy = 0f
        anchorScrollPointer()
        DebugLog.log("scroll", "session begin gen=$scrollGeneration")
    }

    private fun anchorScrollPointer() {
        scrollX = clamp(cursorX, SCROLL_EDGE_MARGIN_PX, screenWidth - SCROLL_EDGE_MARGIN_PX)
        scrollY = clamp(cursorY, SCROLL_EDGE_MARGIN_PX, screenHeight - SCROLL_EDGE_MARGIN_PX)
    }

    /**
     * Decides what the scroll gesture should do next. Safe to call at any time:
     * it is a no-op while a dispatch is in flight, because that dispatch's
     * callback pumps again when it lands.
     */
    private fun pumpScroll() {
        if (scrollDispatchInFlight) return
        if (scrollFingerDown && scrollNeedsReanchor) {
            scrollNeedsReanchor = false
            liftPointer(reanchor = scrollSessionActive)
            return
        }
        if (!scrollFingerDown) {
            if (scrollSessionActive) dispatchTouchDown()
            return
        }
        if (pendingScrollDx != 0f || pendingScrollDy != 0f) {
            dispatchContinuation()
        } else if (!scrollSessionActive) {
            liftPointer(reanchor = false)
        }
    }

    /** The finger landing, not yet moving. Has to go out and come back before any
     * movement can be sent, since continueStroke() needs a stroke that has
     * actually completed. */
    private fun dispatchTouchDown() {
        val generation = scrollGeneration
        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(scrollX, scrollY) }, 0, SCROLL_TOUCH_DOWN_MS, true
        )

        scrollDispatchInFlight = true
        lastSyntheticDispatchAt = SystemClock.uptimeMillis()
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    scrollStroke = stroke
                    scrollFingerDown = true
                    pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    DebugLog.log("scroll", "touch-down cancelled")
                    abandonScroll()
                }
            },
            null
        )
        if (!accepted) {
            scrollDispatchInFlight = false
            DebugLog.log("scroll", "touch-down refused by system")
            abandonScroll()
        }
    }

    private fun dispatchContinuation() {
        val stroke = scrollStroke ?: return
        val generation = scrollGeneration

        val dx = pendingScrollDx
        val dy = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        val startX = scrollX
        val startY = scrollY
        val endX = clamp(startX + dx, SCROLL_EDGE_MARGIN_PX, screenWidth - SCROLL_EDGE_MARGIN_PX)
        val endY = clamp(startY + dy, SCROLL_EDGE_MARGIN_PX, screenHeight - SCROLL_EDGE_MARGIN_PX)

        // Whatever the clamp ate is travel this gesture cannot deliver: hand it
        // back to pending and re-plant, so a long swipe carries on scrolling
        // instead of quietly dying at the edge of the screen.
        val unusedDx = dx - (endX - startX)
        val unusedDy = dy - (endY - startY)
        if (abs(unusedDx) > 0.5f || abs(unusedDy) > 0.5f) {
            pendingScrollDx += unusedDx
            pendingScrollDy += unusedDy
            scrollNeedsReanchor = true
            DebugLog.log("scroll", "out of travel, re-planting")
        }

        if (endX == startX && endY == startY) {
            // Nothing left to travel — skip the empty segment (a zero-length
            // stroke risks being refused outright) and go re-plant.
            scrollNeedsReanchor = true
            pumpScroll()
            return
        }

        scrollX = endX
        scrollY = endY

        val nextStroke = stroke.continueStroke(
            Path().apply { moveTo(startX, startY); lineTo(endX, endY) },
            0, SCROLL_SEGMENT_DURATION_MS, true
        )

        scrollDispatchInFlight = true
        lastSyntheticDispatchAt = SystemClock.uptimeMillis()
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(nextStroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    scrollStroke = nextStroke
                    pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation != scrollGeneration) {
                        pumpScroll()
                        return
                    }
                    DebugLog.log("scroll", "continuation cancelled")
                    abandonScroll()
                }
            },
            null
        )
        if (!accepted) {
            scrollDispatchInFlight = false
            DebugLog.log("scroll", "continuation refused by system")
            abandonScroll()
        }
    }

    /** Lifts the synthetic finger. With [reanchor] set, it is immediately
     * re-planted back at the cursor so a swipe that ran out of screen can keep
     * going; otherwise the drag is simply over. */
    private fun liftPointer(reanchor: Boolean) {
        val stroke = scrollStroke
        scrollStroke = null
        scrollFingerDown = false

        if (stroke == null) {
            if (reanchor) {
                anchorScrollPointer()
                pumpScroll()
            }
            return
        }

        val generation = scrollGeneration
        val end = stroke.continueStroke(
            Path().apply { moveTo(scrollX, scrollY) }, 0, 1L, false
        )
        if (reanchor) anchorScrollPointer()

        scrollDispatchInFlight = true
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(end).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    if (generation == scrollGeneration || scrollSessionActive) pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scrollDispatchInFlight = false
                    pumpScroll()
                }
            },
            null
        )
        if (!accepted) {
            scrollDispatchInFlight = false
            pumpScroll()
        }
    }

    /** Gesture dispatch fell over — drop the whole drag rather than trying to
     * continue from a stroke the system has already torn down. */
    private fun abandonScroll() {
        scrollSessionActive = false
        scrollFingerDown = false
        scrollNeedsReanchor = false
        scrollStroke = null
        pendingScrollDx = 0f
        pendingScrollDy = 0f
    }


    private fun tapAt(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        lastSyntheticDispatchAt = SystemClock.uptimeMillis()
        dispatchGesture(gesture, null, null)
    }

    private fun clamp(value: Float, minVal: Float, maxVal: Float) = max(minVal, min(maxVal, value))


    /**
     * Used to spot the user reaching up and touching the top screen directly, so
     * the cursor can get out of the way — once they're touching the screen, the
     * arrow isn't what they're pointing with any more.
     *
     * Android doesn't hand an accessibility service raw touches from other apps
     * (short of touch exploration, which would change how the whole device
     * behaves), so this reads the interaction events apps emit when something is
     * clicked or scrolled. Our own dispatched gestures raise those too, hence the
     * echo window: an interaction that lands well after anything we sent was a
     * real finger. Best-effort by nature — it can be fooled by an app animating
     * on its own — so it's a setting, and it never hides anything permanently
     * (the next trackpad touch brings the cursor straight back).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!cursorShown || event == null) return
        if (!CursorSettings.hideOnScreenTouch(this)) return
        val interactive = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> true
            else -> false
        }
        if (!interactive) return
        if (SystemClock.uptimeMillis() - lastSyntheticDispatchAt < SYNTHETIC_ECHO_WINDOW_MS) return
        DebugLog.log("cursor", "screen touched directly, hiding cursor")
        idleHandler.removeCallbacks(hideOnIdle)
        setCursorShown(false)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log("cursor", "accessibility service destroyed")
        idleHandler.removeCallbacks(hideOnIdle)
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
        instance = null
    }
}
