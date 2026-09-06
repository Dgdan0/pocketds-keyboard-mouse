package com.pocketds.kbm.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pocketds.kbm.debug.DebugLog
import com.pocketds.kbm.gesture.ReleaseFlick
import com.pocketds.kbm.ime.BottomPanelService
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

    /** The last movement dispatched, and when — the release reads these to work
     * out how fast the finger was going as it let go. */
    /** A selection drag is in progress, so cursor movement drags the held
     * finger along instead of only moving the pointer. */
    private var dragging = false

    /**
     * A selection must not coast. Scrolling wants the throw carried through the
     * release; a drag that flung on lift would run the selection past wherever
     * the finger actually stopped.
     */
    private var suppressFlick = false

    private val flickTracker = ReleaseFlick.Tracker()
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
        /**
         * Margin added on top of the system's long-press threshold. Our
         * synthetic "up" has to land clearly after the threshold or it loses the
         * race and the long-press is silently eaten.
         *
         * This used to be a flat 650ms, chosen to clear an assumed ~500ms
         * threshold. Reading the real value instead means the wait is as short
         * as the device actually allows — and it follows the user's own
         * touch-and-hold delay setting rather than ignoring it.
         */
        private const val LONG_PRESS_MARGIN_MS = 250L
        /** Bounds on the tree walk, so a pathological layout cannot stall a
         * right-click on the main thread. */
        private const val MAX_NODE_DEPTH = 40
        private const val MAX_NODES_SCANNED = 400
        private const val TAP_DISPATCH_RETRIES = 4
        private const val TAP_RETRY_DELAY_MS = 16L
        /** Just long enough to register as a tap, short enough to be unnoticed. */
        private const val CARET_TAP_MS = 40L
        private const val SCROLL_SEGMENT_DURATION_MS = 40L
        /** How long the finger keeps moving as it lifts. Long enough for the
         * velocity to be read, short enough not to be felt as extra travel. */
        private const val FLICK_DURATION_MS = 24L
        /**
         * About 3000px/s. The cap was ten times this, near the fastest fling
         * the platform accepts, and a light flick was reaching the end of a
         * page and springing back off it.
         */
        private const val FLICK_MAX_SPEED_PX_PER_MS = 3f
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
        val fromX = cursorX
        val fromY = cursorY
        cursorX = clamp(cursorX + dx, 0f, screenWidth.toFloat())
        cursorY = clamp(cursorY + dy, 0f, screenHeight.toFloat())
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        windowManager.updateViewLayout(cursorView, cursorParams)

        // While selecting, the held finger goes with the pointer. Sent as the
        // distance actually travelled rather than the requested delta, so the
        // finger cannot drift away from the arrow at the edges of the screen.
        if (dragging) scrollBy(cursorX - fromX, cursorY - fromY)
    }

    fun click() {
        onCursorActivity()
        tapAt(cursorX, cursorY, TAP_DURATION_MS)
    }

    fun rightClick() {
        onCursorActivity()
        // Asking the view under the cursor to long-click itself is instant.
        // Holding a synthetic finger down has to outlast the system's long-press
        // threshold, which is around half a second the user feels every time.
        if (performLongClickUnderCursor()) return
        // Plenty of views respond to a long press without advertising
        // themselves as long-clickable, so the held finger stays as a fallback.
        DebugLog.log("cursor", "no long-clickable view under the cursor, holding instead")
        tapAt(cursorX, cursorY, longPressDurationMs())
    }

    /**
     * Long-clicks whatever the cursor is over, if it will accept one.
     *
     * Needs `canRetrieveWindowContent`, which is why it is worth being narrow
     * about: the window list is only walked at the moment a right-click is
     * requested, nothing about the screen is stored, and only the bounds and
     * the long-clickable flag are read.
     */
    private fun performLongClickUnderCursor(): Boolean {
        val x = cursorX.toInt()
        val y = cursorY.toInt()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<NodeCandidate>()

        for (window in windows.orEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window.displayId != cursorDisplayId()) continue
            // Our own cursor overlay sits exactly under the point by
            // definition, so it must never be a candidate.
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = window.root ?: continue
            collectCandidates(root, depth = 0, nodes = nodes, candidates = candidates)
        }

        // Text first: a long click on a field selects whatever word its caret
        // is on, so on its own it acts in the wrong place. Put the caret where
        // the cursor is pointing and it does exactly the right thing.
        val field = LongClickTarget.textFieldAt(candidates, x, y)
        if (field != null) {
            selectWordUnderCursor(nodes[field.index])
            return true
        }

        // Work outwards from the innermost view, since isLongClickable is
        // advertising rather than truth and the action itself is the only
        // reliable answer. Anything container-sized is skipped: a long click
        // names a view, not a point, so on a web page or a list it would act in
        // the wrong place — the held finger handles those accurately instead.
        val ranked = LongClickTarget.rank(candidates, x, y, maxArea = maxTargetArea())
        for (target in ranked) {
            if (nodes[target.index].performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                DebugLog.log("cursor", "long-clicked the view under the cursor")
                return true
            }
        }
        DebugLog.log("cursor", "${ranked.size} candidate(s) under the cursor, none took a long click")
        return false
    }

    /**
     * Selects the word the cursor is over, by placing the caret there and then
     * asking the field to long-click itself.
     *
     * A plain tap is what moves the caret, and it has to land before the
     * selection is asked for — hence the chained callback rather than two calls
     * in a row. Together they come in around a tenth of a second, against most
     * of a second for holding a finger down, which is the whole point.
     */
    private fun selectWordUnderCursor(node: AccessibilityNodeInfo) {
        val path = Path().apply { moveTo(cursorX, cursorY) }
        val tap = GestureDescription.StrokeDescription(path, 0, CARET_TAP_MS)
        markSyntheticDispatch(CARET_TAP_MS)
        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(tap).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    finishWordSelection(node)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishWordSelection(node)
                }
            },
            null
        )
        if (!dispatched) {
            DebugLog.log("cursor", "caret tap refused, holding instead")
            tapAt(cursorX, cursorY, longPressDurationMs())
        }
    }

    private fun finishWordSelection(node: AccessibilityNodeInfo) {
        // Re-read the node: the tap that moved the caret may have rebuilt it.
        node.refresh()
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            DebugLog.log("cursor", "selected the word under the cursor")
        } else {
            DebugLog.log("cursor", "field would not select, holding instead")
            tapAt(cursorX, cursorY, longPressDurationMs())
        }
    }

    /**
     * How big a view may be and still be an unambiguous long-click target.
     *
     * A third of the screen: a list row or a card is well inside it, while the
     * things that must not be asked — a web page, a full-height list, the decor
     * view — are all far past it.
     */
    private fun maxTargetArea(): Int = screenWidth * screenHeight / 3

    /** The pointer lives on the display the focused app is on, which is the
     * default one — the panel is what sits on the other screen. */
    private fun cursorDisplayId(): Int = Display.DEFAULT_DISPLAY

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        depth: Int,
        nodes: MutableList<AccessibilityNodeInfo>,
        candidates: MutableList<NodeCandidate>
    ) {
        if (depth > MAX_NODE_DEPTH || candidates.size >= MAX_NODES_SCANNED) return

        nodes += node
        candidates += node.toCandidate(index = nodes.size - 1, depth = depth)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            // Whole subtrees the cursor is nowhere near are skipped, which keeps
            // this cheap on a deep layout. Containment is asked of the candidate
            // rather than of Rect, whose own contains() excludes the right and
            // bottom edges that the picking rule includes.
            val candidate = child.toCandidate(index = -1, depth = depth + 1)
            if (candidate.contains(cursorX.toInt(), cursorY.toInt())) {
                collectCandidates(child, depth + 1, nodes, candidates)
            }
        }
    }

    private fun AccessibilityNodeInfo.toCandidate(index: Int, depth: Int): NodeCandidate {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return NodeCandidate(
            index = index,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            longClickable = isLongClickable && isEnabled,
            clickable = isClickable && isEnabled,
            editable = isEditable,
            depth = depth
        )
    }

    /** Android has no right-click, so a long press is the closest system-wide
     * equivalent — it opens context menus the same way. Held just past whatever
     * the system currently considers a long press. */
    private fun longPressDurationMs(): Long =
        ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS

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

    /**
     * Press and hold at the cursor, so moving it drags out a text selection.
     *
     * Built on the scroll machinery rather than beside it: a held finger being
     * dragged is exactly what a scroll already is, and two of those on one
     * screen would fight over the same synthetic pointer. The only difference
     * is which way the finger goes — a scroll moves opposite the fingers, a
     * selection follows the cursor.
     */
    fun beginDrag() {
        onCursorActivity()
        if (dragging) return
        dragging = true
        DebugLog.log("cursor", "drag started, selecting from the cursor")
        beginScrollSession()
        // After the session starts, which clears it.
        suppressFlick = true
        pumpScroll()
    }

    fun endDrag() {
        if (!dragging) return
        dragging = false
        DebugLog.log("cursor", "drag ended")
        endScroll()
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
        // Speed from the previous drag would otherwise fling this one on lift.
        clearSegmentVelocity()
        suppressFlick = false
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

        flickTracker.add(
            dx = endX - startX,
            dy = endY - startY,
            durationMs = SCROLL_SEGMENT_DURATION_MS,
            atMs = SystemClock.uptimeMillis()
        )

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
        // Keep moving through the release when the finger was still going.
        // Ending on a stationary segment hands the app a release velocity of
        // zero, and its fling never triggers — which is why scrolling stopped
        // dead the instant you lifted. Letting the app fling is better than
        // simulating decay here: it matches whatever that app already does.
        val velocity = if (reanchor || suppressFlick) null
            else flickTracker.velocity(SystemClock.uptimeMillis())
        val flick = velocity?.let {
            ReleaseFlick.segment(
                vx = it.vx,
                vy = it.vy,
                flickMs = FLICK_DURATION_MS,
                maxSpeedPxPerMs = FLICK_MAX_SPEED_PX_PER_MS
            )
        }
        val endPath = Path().apply {
            moveTo(scrollX, scrollY)
            if (flick != null) {
                lineTo(
                    clamp(scrollX + flick.dx, SCROLL_EDGE_MARGIN_PX, screenWidth - SCROLL_EDGE_MARGIN_PX),
                    clamp(scrollY + flick.dy, SCROLL_EDGE_MARGIN_PX, screenHeight - SCROLL_EDGE_MARGIN_PX)
                )
            }
        }
        val end = stroke.continueStroke(endPath, 0, flick?.durationMs ?: 1L, false)
        // One line per release, so "it stopped dead again" can be answered by
        // looking rather than by guessing.
        if (!reanchor) {
            DebugLog.log(
                "scroll",
                if (flick == null) "released flat" else "released with a flick of ${flick.dx}, ${flick.dy}"
            )
        }
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
        clearSegmentVelocity()
        scrollSessionActive = false
        scrollFingerDown = false
        scrollNeedsReanchor = false
        scrollStroke = null
        pendingScrollDx = 0f
        pendingScrollDy = 0f
    }


    private fun tapAt(x: Float, y: Float, durationMs: Long, attempt: Int = 0) {
        // A right-click can follow immediately after the lift that ends a
        // scroll — a two-finger tap whose own wobble started one. The system
        // takes a single gesture at a time, so dispatching into the tail of
        // that lift is silently dropped, and the tap goes missing. The lift is
        // a 1ms stroke, so waiting for it costs nothing anyone can perceive.
        if (scrollDispatchInFlight && attempt < TAP_DISPATCH_RETRIES) {
            idleHandler.postDelayed({ tapAt(x, y, durationMs, attempt + 1) }, TAP_RETRY_DELAY_MS)
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        markSyntheticDispatch(durationMs)
        dispatchGesture(gesture, null, null)
    }

    /**
     * Notes that we are about to touch the screen ourselves, so the interaction
     * the app reports back is not mistaken for the user reaching up to the top
     * screen (which hides the cursor, on the reasoning that a finger has taken
     * over from the pointer).
     *
     * Stamped forward to when the gesture will *finish*, because the app only
     * reacts then. A long press outlasts the echo window on its own, so every
     * right-click was hiding the cursor.
     */
    private fun markSyntheticDispatch(durationMs: Long = 0L) {
        lastSyntheticDispatchAt = SystemClock.uptimeMillis() + durationMs
    }

    /** So a new scroll cannot fling on the back of the previous one's speed. */
    private fun clearSegmentVelocity() = flickTracker.reset()

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
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            BottomPanelService.instance?.onBottomScreenWindowsChanged()
        }
        if (!cursorShown) return
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

    /**
     * The frontmost app on [displayId], or null if only our own windows or
     * nothing at all are there.
     *
     * getWindows() covers the default display only, so the bottom screen needs
     * the all-displays variant.
     */
    fun frontmostAppPackage(displayId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val onDisplay = windowsOnAllDisplays.get(displayId) ?: return null
        return onDisplay
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            // Topmost first, which is the one the user is looking at.
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { window ->
                window.root?.packageName?.toString()?.takeIf { it != packageName }
            }
    }

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
