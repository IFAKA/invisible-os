package com.faka.invisible.memory

import java.util.Random

/** Pure rules of Memory. A tone is 0 (low, one click) or 1 (high, two clicks). The sequence grows by one tone each round. */
class MemoryCore(private val rng: Random = Random()) {
    enum class Step { RIGHT, ROUND_DONE, WRONG }

    val seq = ArrayList<Int>()
    private var pos = 0
    /** Rounds copied without a mistake. */
    var score = 0
        private set

    /** Adds one tone and returns the whole sequence to play. The first two tones are low then high, so both are taught at once. */
    fun newRound(): List<Int> {
        seq.add(if (seq.size < 2) seq.size else rng.nextInt(2))
        pos = 0
        return seq.toList()
    }

    /** The player gave tone [sym] (null = a click count that is no tone). */
    fun answer(sym: Int?): Step {
        if (sym == null || sym != seq[pos]) return Step.WRONG
        pos++
        if (pos < seq.size) return Step.RIGHT
        score = seq.size
        return Step.ROUND_DONE
    }

    companion object {
        /** One click is low, two clicks are high, anything else is no tone. */
        fun symbolOf(clicks: Int): Int? = when (clicks) { 1 -> 0; 2 -> 1; else -> null }
    }
}
