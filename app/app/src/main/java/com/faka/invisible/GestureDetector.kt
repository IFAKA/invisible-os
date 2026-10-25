package com.faka.invisible

/** CLICK_HOLD = a click, then a second press held: fast scroll starts. CLICK_HOLD_END = that press is released. A plain hold is HOLD (exit). */
enum class Gesture { SINGLE, DOUBLE, TRIPLE, QUAD, HOLD, CLICK_HOLD, CLICK_HOLD_END }

/** Pure classifier: presses are (downTime, upTime) in ms. */
object GestureRules {
    const val HOLD_MS = 500L
    const val GAP_MS = 350L

    /** The last press may be a hold. A hold that is the 2nd press (the one before ended less than GAP_MS earlier) is CLICK_HOLD. */
    fun classify(presses: List<Pair<Long, Long>>): Gesture? {
        if (presses.isEmpty()) return null
        if (presses.last().let { it.second - it.first >= HOLD_MS }) {
            val prev = presses.getOrNull(presses.size - 2)
            return if (prev != null && presses.last().first - prev.second < GAP_MS) Gesture.CLICK_HOLD else Gesture.HOLD
        }
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
    private var clickHold = false

    fun down(time: Long) {
        if (downAt >= 0) return
        cancel()
        downAt = time
        holdFired = false; clickHold = false
        schedule(GestureRules.HOLD_MS) {
            if (downAt >= 0 && !holdFired) {
                holdFired = true
                fireHold(downAt, downAt + GestureRules.HOLD_MS, GestureRules.HOLD_MS)
            }
        }
    }

    private fun fireHold(d: Long, end: Long, dur: Long) {
        val g = GestureRules.classify(presses + (d to end)) ?: Gesture.HOLD
        presses.clear()
        clickHold = g == Gesture.CLICK_HOLD
        onGesture(g, dur)
    }

    fun up(time: Long) {
        if (downAt < 0) return
        cancel()
        val d = downAt
        downAt = -1
        if (holdFired) {
            presses.clear()
            if (clickHold) { clickHold = false; onGesture(Gesture.CLICK_HOLD_END, time - d) }
            return
        }
        if (time - d >= GestureRules.HOLD_MS) {
            fireHold(d, time, time - d)
            if (clickHold) { clickHold = false; onGesture(Gesture.CLICK_HOLD_END, time - d) }
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
