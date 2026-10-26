package com.faka.invisible.echo

import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure rules of ECHO (see GAME-ECHO.md). The phone plays a rhythm on a steady beat; you play it back with clicks.
 * No Android types here. Times are milliseconds from the start of a bar.
 */

/** What one level asks. A bar has 8 slots (eighth notes); a phrase is 1 or 2 bars. */
data class EchoLevel(
    val no: Int,
    val bpm: Int,
    val bars: Int,
    val hits: Int,
    val offbeat: Double,        // chance that a hit sits on an off-beat slot (the "and")
    val guide: Boolean,         // false: the steady beat goes silent in your turn
) {
    val slotMs: Double get() = 30000.0 / bpm
    val slots: Int get() = 8 * bars
    val barMs: Double get() = slotMs * slots
    /** Click window either side of a hit: wide at first (170 ms), 110 ms from level 10. Always under half a slot. */
    val goodMs: Double get() = min(EchoJudge.GOOD_MS + 60.0 * (10 - min(no, 10)) / 9, 0.42 * slotMs)
    val perfectMs: Double get() = min(EchoJudge.PERFECT_MS, 0.5 * goodMs)
}

object EchoLadder {
    fun level(no: Int): EchoLevel {
        val n = no.coerceAtLeast(1)
        val bars = if (n >= 10) 2 else 1
        val maxHits = 4 * bars + 2
        return EchoLevel(
            no = n,
            bpm = 70 + min(n, 30) * 3 / 2,
            bars = bars,
            hits = (2 + n / 2).coerceAtMost(maxHits + n / 6).coerceAtMost(8 * bars - 2),
            offbeat = if (n < 3) 0.0 else min(0.55, 0.08 * (n - 2)),
            guide = n < 20,
        )
    }

    /** The slots (0 until level.slots) the robot hits. Sorted, distinct, never everything on one side. */
    fun pattern(level: EchoLevel, rng: Random): List<Int> {
        val slots = level.slots
        val on = (0 until slots step 2).toList()
        val off = (1 until slots step 2).toList()
        val chosen = sortedSetOf<Int>()
        if (level.no < 6) chosen.add(0)     // early on, the first hit is always on the first beat
        while (chosen.size < level.hits) {
            val pool = if (rng.nextDouble() < level.offbeat) off else on
            chosen.add(pool[rng.nextInt(pool.size)])
        }
        return chosen.toList()
    }
}

enum class Grade { PERFECT, GOOD }
data class Hit(val slot: Int, val grade: Grade?, val deltaMs: Double?)   // grade null = missed

data class BarResult(val hits: List<Hit>, val extras: Int) {
    val cleared get() = extras == 0 && hits.all { it.grade != null }
    val perfect get() = cleared && hits.all { it.grade == Grade.PERFECT }
    val points get() = hits.sumOf { when (it.grade) { Grade.PERFECT -> 3; Grade.GOOD -> 1; null -> 0 }.toInt() }
}

/** Matches your clicks to the robot's hits and learns how early or late you tend to click (button + earbud delay). */
class EchoJudge {
    companion object {
        const val GOOD_MS = 110.0
        const val PERFECT_MS = 55.0
        private const val MEMORY = 16
    }

    private val deltas = ArrayDeque<Double>()
    /** Median signed error of recent clicks; subtracted from every click. */
    val biasMs: Double get() = if (deltas.isEmpty()) 0.0 else deltas.sorted().let { it[it.size / 2] } * min(1.0, deltas.size / 3.0)

    /** @param clicks click times in ms from bar start, in order. */
    fun judge(level: EchoLevel, pattern: List<Int>, clicks: List<Double>): BarResult {
        val bias = biasMs
        val want = pattern.map { it * level.slotMs }
        val used = BooleanArray(want.size)
        val delta = arrayOfNulls<Double>(want.size)
        var extras = 0
        val seen = ArrayList<Double>()
        for (raw in clicks) {
            val c = raw - bias
            var best = -1
            for (i in want.indices) if (!used[i] && abs(c - want[i]) <= level.goodMs && (best < 0 || abs(c - want[i]) < abs(c - want[best]))) best = i
            if (best < 0) extras++ else { used[best] = true; delta[best] = c - want[best]; seen.add(raw - want[best]) }
        }
        for (d in seen) { deltas.addLast(d); if (deltas.size > MEMORY) deltas.removeFirst() }
        val hits = want.indices.map { i ->
            val d = delta[i]
            Hit(pattern[i], when { d == null -> null; abs(d) <= level.perfectMs -> Grade.PERFECT; else -> Grade.GOOD }, d)
        }
        return BarResult(hits, extras)
    }
}

/** Three hearts. A cleared bar moves up a level; a failed bar costs a heart and repeats the level; an all-perfect bar heals one. */
class EchoRun(val maxHearts: Int = 3) {
    var level = 1; private set
    var hearts = maxHearts; private set
    var score = 0; private set
    var cleared = 0; private set
    val over get() = hearts <= 0

    fun record(r: BarResult) {
        if (r.cleared) {
            score += r.points * (1 + level / 5)
            cleared++; level++
            if (r.perfect && hearts < maxHearts) hearts++
        } else hearts--
    }
}
