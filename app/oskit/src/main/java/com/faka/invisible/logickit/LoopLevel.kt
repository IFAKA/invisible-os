package com.faka.invisible.logickit

import com.faka.invisible.voicekit.LoopPace

/**
 * The beginner pace of the letter loop, one level per person, shared by Write and the home letters (Ticker users were trained over sessions;
 * Nomon lets the speed rise as the user gets good). Level 0 = one channel (both ears the same), normal speech, 800 ms an item; it rises in steps
 * 800 -> 600 -> 500 -> 420 -> 350 ms, then speech 1.25x -> 1.5x -> 1.75x, then two ears (the top level, the old fixed pace).
 * [chose] is told every choice of the loop: [cleanToRise] clean picks in a row (a pick not followed at once by Undo) raise the level;
 * [undosToFall] Undos in a row lower it by one. [save] gets the new level (the caller keeps it in prefs). Pure.
 */
class LoopLevel(start: Int = 0, private val cleanToRise: Int = 5, private val undosToFall: Int = 2, private val save: (Int) -> Unit = {}) {
    var level = start.coerceIn(0, TOP); private set
    private var clean = 0
    private var undos = 0
    val pace: LoopPace get() = PACES[level]

    /** A choice of the loop ([undo] = it was Undo). Returns true when the level changed. */
    fun chose(undo: Boolean): Boolean {
        if (undo) {
            clean = 0; undos++
            if (undos >= undosToFall) { undos = 0; return move(-1) }
            return false
        }
        undos = 0; clean++
        if (clean >= cleanToRise) { clean = 0; return move(1) }
        return false
    }

    private fun move(d: Int): Boolean {
        val to = (level + d).coerceIn(0, TOP)
        if (to == level) return false
        level = to; save(to); return true
    }

    /** `level=<n> item=<ms> speed=<x> ears=<1|2>`, for the trace. */
    fun describe(): String = pace.let { "level=$level item=${it.itemMs} speed=${it.speed} ears=${it.ears}" }

    companion object {
        val PACES = listOf(
            LoopPace(800, 1.0, 1), LoopPace(600, 1.0, 1), LoopPace(500, 1.0, 1), LoopPace(420, 1.0, 1), LoopPace(350, 1.0, 1),
            LoopPace(350, 1.25, 1), LoopPace(350, 1.5, 1), LoopPace(350, 1.75, 1), LoopPace(350, 1.75, 2),
        )
        val TOP = PACES.size - 1
        /** The prefs key of the level (the same for Write and the home letters: it is the same person). */
        const val KEY = "loop_level"
    }
}
