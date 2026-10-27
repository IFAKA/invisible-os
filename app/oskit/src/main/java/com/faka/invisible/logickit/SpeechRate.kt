package com.faka.invisible.logickit

/** The voice speed the engine really uses: the slider value, plus a little with use (0.1 per 20 successful picks, at most +0.2), never above 1.8. */
object SpeechRate {
    const val MAX = 1.8f
    const val MAX_BONUS = 0.2f
    fun effective(slider: Float, picks: Int): Float = (slider + minOf(MAX_BONUS, 0.1f * (picks / 20))).coerceAtMost(MAX)
}
