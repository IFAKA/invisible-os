package com.faka.invisible.voicekit

/** CLICK_HOLD = a click, then a second press held: fast scroll starts. CLICK_HOLD_END = that press is released. A plain hold is HOLD (exit). */
enum class Gesture { SINGLE, DOUBLE, TRIPLE, QUAD, HOLD, CLICK_HOLD, CLICK_HOLD_END }

/** Pure classifier: presses are (downTime, upTime) in ms. */
object GestureRules {
    const val HOLD_MS = Pacing.HOLD_MS
    const val GAP_MS = Pacing.GAP_MS

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
