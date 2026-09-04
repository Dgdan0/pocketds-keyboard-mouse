package com.pocketds.kbm.gesture

/**
 * Writes touch sequences the way a finger would perform them, so tests read as
 * a description of a gesture rather than a wall of `TouchSample` constructors.
 *
 * Advances a clock on every step, because durations decide taps and the
 * recogniser takes time as data rather than reading one.
 */
class TouchScript(
    config: GestureConfig = GestureConfig.DEFAULT,
    private val stepMs: Long = 16L
) {
    private val recognizer = TrackpadGestureRecognizer(config)
    private var nowMs = 0L
    private val down = mutableListOf<Pointer>()

    /** Everything emitted so far, for assertions about a whole gesture. */
    val all = mutableListOf<GestureCommand>()

    fun down(x: Float, y: Float, id: Int = 0): List<GestureCommand> {
        down.clear()
        down += Pointer(id, x, y)
        return feed(TouchAction.DOWN, id)
    }

    fun pointerDown(id: Int, x: Float, y: Float, afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        down += Pointer(id, x, y)
        return feed(TouchAction.POINTER_DOWN, id)
    }

    /** Moves one finger to an absolute position, leaving the others put. */
    fun moveTo(id: Int, x: Float, y: Float, afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        replace(Pointer(id, x, y))
        return feed(TouchAction.MOVE, NO_POINTER)
    }

    fun moveBy(id: Int, dx: Float, dy: Float, afterMs: Long = stepMs): List<GestureCommand> {
        val current = down.first { it.id == id }
        return moveTo(id, current.x + dx, current.y + dy, afterMs)
    }

    /** Moves every finger down by the same amount, as a real two-finger scroll does. */
    fun moveAllBy(dx: Float, dy: Float, afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        val moved = down.map { Pointer(it.id, it.x + dx, it.y + dy) }
        down.clear()
        down += moved
        return feed(TouchAction.MOVE, NO_POINTER)
    }

    fun pointerUp(id: Int, afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        // The lifting pointer is still present in the event, mirroring MotionEvent.
        val commands = feed(TouchAction.POINTER_UP, id)
        down.removeAll { it.id == id }
        return commands
    }

    fun up(afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        val id = down.firstOrNull()?.id ?: NO_POINTER
        return feed(TouchAction.UP, id)
    }

    fun cancel(afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        return feed(TouchAction.CANCEL, NO_POINTER)
    }

    /** A move delivered without a preceding down, as Split's revealed trackpad
     * receives when it starts intercepting part-way through a gesture. */
    fun moveWithoutDown(x: Float, y: Float, id: Int = 0, afterMs: Long = stepMs): List<GestureCommand> {
        advance(afterMs)
        down.clear()
        down += Pointer(id, x, y)
        return feed(TouchAction.MOVE, NO_POINTER)
    }

    private fun replace(pointer: Pointer) {
        down.removeAll { it.id == pointer.id }
        down += pointer
    }

    private fun advance(ms: Long) {
        nowMs += ms
    }

    private fun feed(action: TouchAction, actionPointerId: Int): List<GestureCommand> {
        val commands = recognizer.onTouch(
            TouchSample(action, down.toList(), actionPointerId, nowMs)
        )
        all += commands
        return commands
    }
}
