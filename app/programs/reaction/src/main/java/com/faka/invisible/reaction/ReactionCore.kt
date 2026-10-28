package com.faka.invisible.reaction

import java.util.Random

/** Pure rules of Reaction: 5 rounds, a random wait before each beep, the average of the times. */
class ReactionCore(private val rng: Random = Random()) {
    val times = ArrayList<Int>()
    var faults = 0
        private set

    val done get() = times.size >= ROUNDS

    /** How long to wait before the beep: between 1.5 and 5 seconds, never the same rhythm. */
    fun waitMs(): Long = MIN_WAIT_MS + rng.nextInt((MAX_WAIT_MS - MIN_WAIT_MS).toInt())

    fun record(ms: Int) { times.add(ms.coerceIn(0, SLOW_MS)) }
    fun fault() { faults++ }

    /** Average of the rounds so far, whole ms (0 when none). */
    fun average(): Int = if (times.isEmpty()) 0 else (times.sum().toDouble() / times.size).toInt()

    companion object {
        const val ROUNDS = 5
        const val MIN_WAIT_MS = 1500L
        const val MAX_WAIT_MS = 5000L
        /** No click this long after the beep: the round counts as this slow. */
        const val SLOW_MS = 3000
    }
}
