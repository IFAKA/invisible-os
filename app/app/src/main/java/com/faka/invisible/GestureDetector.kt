package com.faka.invisible

enum class Gesture { SINGLE, DOUBLE, TRIPLE, QUAD, HOLD }

/** Pure classifier: presses are (downTime, upTime) in ms. */
object GestureRules {
    const val HOLD_MS = 500L
    const val GAP_MS = 350L

    fun classify(presses: List<Pair<Long, Long>>): Gesture? {
        if (presses.isEmpty()) return null
        if (presses.last().let { it.second - it.first >= HOLD_MS }) return Gesture.HOLD
        return when (presses.size) { 1 -> Gesture.SINGLE; 2 -> Gesture.DOUBLE; 3 -> Gesture.TRIPLE; else -> Gesture.QUAD }
    }
}

/**
 * Feed down()/up() with event times. Timers are injected (schedule/cancel) so it is testable.
 * Fires onGesture(gesture, durationMs) where duration is the last press length.
 */
class GestureDetector(
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val onGesture: (Gesture, Long) -> Unit,
) {
    private val presses = ArrayList<Pair<Long, Long>>()
    private var downAt = -1L
    private var holdFired = false

    fun down(time: Long) {
        if (downAt >= 0) return
        cancel()
        downAt = time
        holdFired = false
        schedule(GestureRules.HOLD_MS) {
            if (downAt >= 0 && !holdFired) {
                holdFired = true
                presses.clear()
                onGesture(Gesture.HOLD, GestureRules.HOLD_MS)
            }
        }
    }

    fun up(time: Long) {
        if (downAt < 0) return
        cancel()
        val d = downAt
        downAt = -1
        if (holdFired) { presses.clear(); return }
        if (time - d >= GestureRules.HOLD_MS) {
            presses.clear()
            onGesture(Gesture.HOLD, time - d)
            return
        }
        presses.add(d to time)
        schedule(GestureRules.GAP_MS) {
            val g = GestureRules.classify(presses)
            val dur = presses.last().let { it.second - it.first }
            presses.clear()
            if (g != null) onGesture(g, dur)
        }
    }
}

/**
 * Drops a key event that another source (media session vs accessibility) already delivered:
 * same action, downTime within windowMs, different source.
 */
class Deduper(private val windowMs: Long = 150) {
    private class Seen(val source: String, val isDown: Boolean, val downTime: Long)
    private val seen = ArrayList<Seen>()

    /** true = new event, process it. false = duplicate from the other source. */
    fun accept(source: String, isDown: Boolean, downTime: Long): Boolean {
        seen.removeAll { downTime - it.downTime > 5000 }
        val dup = seen.any { it.source != source && it.isDown == isDown && Math.abs(it.downTime - downTime) <= windowMs }
        if (!dup) seen.add(Seen(source, isDown, downTime))
        return !dup
    }
}
