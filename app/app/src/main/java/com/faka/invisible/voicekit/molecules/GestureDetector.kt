package com.faka.invisible.voicekit

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
        // Swapped on purpose: a plain hold is the fast-scroll gesture (CLICK_HOLD, with an END), click-then-hold is HOLD (exit).
        val c = GestureRules.classify(presses + (d to end)) ?: Gesture.HOLD
        val g = when (c) {
            Gesture.HOLD -> Gesture.CLICK_HOLD
            Gesture.CLICK_HOLD -> Gesture.HOLD
            else -> c
        }
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
