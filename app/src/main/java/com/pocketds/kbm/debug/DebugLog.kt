package com.pocketds.kbm.debug

import android.util.Log

/**
 * Always-on ring buffer of the state transitions that actually matter when
 * something misbehaves on the device: IME session start/end, panel expand and
 * collapse (and what caused it), presentation create/dismiss, gesture dispatch
 * results, mode changes.
 *
 * The point is that diagnosing "it just hid itself" shouldn't need a rebuild
 * with hand-added Log.d calls plus a logcat trawl through vendor noise. The
 * trace is always being recorded, it's readable on the device itself under
 * Settings, and it survives the thing you were doing (so you can go look at it
 * afterwards). Everything also goes to logcat under a single tag for when a
 * cable is handy.
 */
object DebugLog {

    const val TAG = "PocketDS"
    private const val CAPACITY = 400

    private val entries = ArrayDeque<String>()
    private var startedAt = System.currentTimeMillis()

    /**
     * @param area short subsystem label — "ime", "panel", "scroll", "mode",
     *   "cursor" — so a trace can be skimmed for the part that misbehaved.
     */
    @Synchronized
    fun log(area: String, message: String) {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000f
        val line = "%8.2f  %-7s %s".format(seconds, area, message)
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(line)
        Log.d(TAG, "$area | $message")
    }

    /** Newest last, ready to render. */
    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        startedAt = System.currentTimeMillis()
    }
}
