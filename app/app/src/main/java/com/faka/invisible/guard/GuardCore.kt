package com.faka.invisible.guard

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure rules of GUARD (see GAME-GUARD.md). An enemy is announced, then three steady steps; you click ON the fourth beat.
 * No Android types here. Times are milliseconds from the enemy's announce sound (beat 0).
 */
enum class Kind { THIEF, KNIGHT, DECOY, FAST_THIEF, OGRE }

data class Enemy(
    val kind: Kind,
    val practice: Boolean,
    val bpm: Double,
    val goodMs: Double,
    val perfectMs: Double,
    val left: Boolean,
) {
    val beatMs: Double get() = 60000.0 / bpm
    val decoy get() = kind == Kind.DECOY
    /** The beats to click on: the 4th (knight: 4th and 5th). A decoy has none. */
    val strikeBeats: List<Int> get() = when (kind) { Kind.DECOY -> emptyList(); Kind.KNIGHT -> listOf(4, 5); else -> listOf(4) }
    val strikesMs: List<Double> get() = strikeBeats.map { it * beatMs }
    /** The announce is beat 0, the steps are beats 1 to 3 (a decoy has only the bell). */
    val stepBeats: List<Int> get() = if (decoy) emptyList() else listOf(1, 2, 3)
    /** The ogre's off-beat fake-out, half a beat before its real thud. */
    val fakeMs: Double? get() = if (kind == Kind.OGRE) 3.5 * beatMs else null
    /** When a decoy stops being dangerous / the last strike is settled. */
    val endMs: Double get() = if (decoy) 4.5 * beatMs else strikesMs.last() + goodMs
}

object GuardLadder {
    const val BASE_BPM = 80.0
    const val MAX_BPM = 140.0
    const val HEARTS = 3
    const val STAGES = 5            // thief, knight, decoy, fast thief, ogre; then mixed waves
    const val PRACTICE = 2
    const val REAL_PER_STAGE = 4
    const val WAVE = 5

    /** Click window either side of a strike: wide (180 ms) at first, shrinking slowly to 100 ms. Never more than 0.4 of a beat. */
    fun good(real: Int, beatMs: Double) = min(max(100.0, 180.0 - 2.0 * real), 0.4 * beatMs)
    fun perfect(good: Double) = min(60.0, 0.5 * good)

    fun bpmFor(kind: Kind, base: Double) = when (kind) {
        Kind.FAST_THIEF -> min(base * 1.35, 150.0)
        Kind.OGRE -> base * 0.7
        else -> base
    }

    fun enemy(kind: Kind, practice: Boolean, base: Double, real: Int, left: Boolean): Enemy {
        val bpm = bpmFor(kind, base)
        val g = good(real, 60000.0 / bpm)
        return Enemy(kind, practice, bpm, g, perfect(g), left)
    }

    fun stageKind(stage: Int) = when (stage) { 1 -> Kind.THIEF; 2 -> Kind.KNIGHT; 3 -> Kind.DECOY; 4 -> Kind.FAST_THIEF; else -> Kind.OGRE }
}

/** What one click did. EARLY and STRAY both sound like the whoosh; BAIT hurts; AFTER is silent (the thud already sounded). */
enum class Verdict { PERFECT, GOOD, EARLY, STRAY, BAIT, AFTER }

/** The settled result of one enemy. */
data class Outcome(
    val kind: Kind,
    val practice: Boolean,
    val hurt: Boolean,
    val perfects: Int,
    val goods: Int,
    val clicks: Int,
    val early: Boolean,
    val late: Boolean,
) {
    val decoyOk get() = kind == Kind.DECOY && !hurt
    val points get() = perfects * 3 + goods + if (decoyOk) 1 else 0
}

/**
 * Judges clicks against the strikes and learns how early or late you click (button + earbud delay): the median of recent
 * click errors, clamped to +-300 ms, shifts every window. A tap-along ([GuardCalibration]) can set it at once.
 */
class GuardJudge(saved: Double = 0.0) {
    companion object {
        const val MAX_BIAS = 300.0
        const val NEAR = 350.0
        private const val MEMORY = 16
    }

    private val deltas = ArrayDeque<Double>()
    init { setBias(saved) }

    val biasMs: Double get() = if (deltas.isEmpty()) 0.0 else (deltas.sorted().let { it[it.size / 2] } * min(1.0, deltas.size / 3.0)).coerceIn(-MAX_BIAS, MAX_BIAS)

    /** Start from a known delay (saved, or measured by the tap-along). */
    fun setBias(ms: Double) { deltas.clear(); if (ms != 0.0) repeat(3) { deltas.addLast(ms.coerceIn(-MAX_BIAS, MAX_BIAS)) } }

    inner class Encounter(val enemy: Enemy) {
        val bias = biasMs
        private val strikes = enemy.strikesMs
        private val used = BooleanArray(strikes.size)
        private val missed = BooleanArray(strikes.size)
        private val grade = arrayOfNulls<Verdict>(strikes.size)
        private val clicks = ArrayList<Double>()
        private var hurt = false
        private var early = false
        private var late = false

        fun matched(i: Int) = used[i]
        fun clickCount() = clicks.size

        /** @param ms click time from the announce. */
        fun click(ms: Double): Verdict {
            clicks.add(ms)
            val c = ms - bias
            if (enemy.decoy) {
                if (c >= 0 && c <= enemy.endMs) { hurt = true; return Verdict.BAIT }
                return Verdict.STRAY
            }
            var best = -1
            for (i in strikes.indices) if (!used[i] && !missed[i] && abs(c - strikes[i]) <= enemy.goodMs && (best < 0 || abs(c - strikes[i]) < abs(c - strikes[best]))) best = i
            if (best >= 0) {
                used[best] = true
                val v = if (abs(c - strikes[best]) <= enemy.perfectMs) Verdict.PERFECT else Verdict.GOOD
                grade[best] = v
                return v
            }
            val fake = enemy.fakeMs
            if (fake != null && !used[0] && !missed[0] && abs(c - fake) <= 0.25 * enemy.beatMs) { hurt = true; return Verdict.BAIT }
            if (strikes.indices.any { !used[it] && !missed[it] && c < strikes[it] - enemy.goodMs }) { early = true; return Verdict.EARLY }
            if (strikes.indices.any { missed[it] && c > strikes[it] && c - strikes[it] <= NEAR }) { late = true; return Verdict.AFTER }
            return Verdict.STRAY
        }

        /** The window of strike [i] closed with no valid click: the enemy hits. */
        fun miss(i: Int) { if (!used[i]) { missed[i] = true; hurt = true } }

        fun finish(): Outcome {
            for (i in strikes.indices) if (!used[i]) { missed[i] = true; hurt = true }
            // learn the delay: the click nearest each strike (within NEAR) is one sample
            for (i in strikes.indices) {
                val near = clicks.filter { c -> strikes.indices.minByOrNull { abs(c - strikes[it]) } == i && abs(c - strikes[i]) <= NEAR }.minByOrNull { abs(it - strikes[i]) }
                if (near != null) { deltas.addLast(near - strikes[i]); if (deltas.size > MEMORY) deltas.removeFirst() }
            }
            return Outcome(enemy.kind, enemy.practice, hurt, grade.count { it == Verdict.PERFECT }, grade.count { it == Verdict.GOOD }, clicks.size, early, late)
        }
    }
}

/** Tap-along calibration: the median offset between ticks and the clicks nearest to them (outliers over 400 ms ignored). */
object GuardCalibration {
    const val BPM = 100.0
    const val TICKS = 6
    val tickMs: List<Double> get() = (0 until TICKS).map { it * 60000.0 / BPM }
    /** @return the delay in ms (positive = you click late), or null when fewer than 3 usable clicks. */
    fun offset(ticks: List<Double>, clicks: List<Double>): Double? {
        val e = clicks.mapNotNull { c -> ticks.minByOrNull { abs(c - it) }?.let { c - it } }.filter { abs(it) <= 400.0 }
        if (e.size < 3) return null
        return e.sorted()[e.size / 2].coerceIn(-GuardJudge.MAX_BIAS, GuardJudge.MAX_BIAS)
    }
}

/**
 * The run: 3 hearts; stages 1 to 5 teach one enemy each (2 free practice, 4 real); then random waves of 5 with the tempo
 * rising from 80 toward 140 bpm. Two hearts lost in one wave drop the tempo one step. A streak of 5 perfects heals one heart.
 */
class GuardRun(startStage: Int = 1) {
    var stage = startStage; private set
    var hearts = GuardLadder.HEARTS; private set
    var score = 0; private set
    var streak = 0; private set
    var real = 0; private set            // real enemies met
    var encounters = 0; private set      // all enemies met, practice too
    var wave = 0; private set
    var drops = 0; private set
    var healed = false; private set
    private var inStage = 0
    private var inWave = 0
    private var waveLoss = 0
    private var plan = listOf<Kind>()
    val over get() = hearts <= 0
    val mixed get() = stage > GuardLadder.STAGES
    /** True while the next enemy is the first of its stage (the game introduces it). */
    val stageStart get() = !mixed && inStage == 0
    /** One id per stage / wave: things that happen "once per wave" use it. */
    val waveId get() = if (mixed) 100 + wave else stage

    val baseBpm: Double get() = if (!mixed) GuardLadder.BASE_BPM else (min(GuardLadder.MAX_BPM, GuardLadder.BASE_BPM + 4.0 * wave) - 10.0 * drops).coerceAtLeast(60.0)

    fun next(rng: Random): Enemy {
        val left = rng.nextBoolean()
        if (mixed) return GuardLadder.enemy(Kind.values()[rng.nextInt(Kind.values().size)], false, baseBpm, real, left)
        if (inStage == 0) {
            val k = GuardLadder.stageKind(stage)
            val realKinds = if (k == Kind.DECOY) listOf(Kind.DECOY, Kind.THIEF, Kind.DECOY, Kind.DECOY).shuffled(rng) else List(GuardLadder.REAL_PER_STAGE) { k }
            plan = List(GuardLadder.PRACTICE) { k } + realKinds
        }
        return GuardLadder.enemy(plan[inStage], inStage < GuardLadder.PRACTICE, baseBpm, real, left)
    }

    fun record(o: Outcome) {
        healed = false
        encounters++
        if (!o.practice) {
            real++
            if (o.hurt) { hearts--; streak = 0; waveLoss++ }
            else {
                score += o.points * (if (mixed) 5 + wave else stage)
                streak = if (o.perfects > 0 && o.goods == 0) streak + o.perfects else if (o.decoyOk) streak else 0
                if (streak >= 5 && hearts < GuardLadder.HEARTS) { hearts++; streak = 0; healed = true }
            }
        }
        if (mixed) {
            inWave++
            if (waveLoss >= 2) { drops++; waveLoss = 0 }
            if (inWave >= GuardLadder.WAVE) { wave++; inWave = 0; waveLoss = 0 }
        } else if (!over) {
            inStage++
            if (inStage >= GuardLadder.PRACTICE + GuardLadder.REAL_PER_STAGE) { stage++; inStage = 0; waveLoss = 0 }
        }
    }
}
