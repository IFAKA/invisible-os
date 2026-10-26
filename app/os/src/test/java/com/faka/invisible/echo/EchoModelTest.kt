package com.faka.invisible.echo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.min

/**
 * The "easy to learn, hard to master" check, by simulated players. A player has: click noise (ms), a constant click
 * delay (ms), and a memory span (how many hits it can hold; one more hit past the span is forgotten at random).
 */
class EchoModelTest {
    private class Player(val name: String, val jitter: Double, val delay: Double, val span: Int, val offbeatPenalty: Double)

    private val novice = Player("novice", 45.0, 60.0, 5, 1.8)
    private val good = Player("good", 28.0, 30.0, 8, 1.3)
    private val expert = Player("expert", 15.0, 10.0, 12, 1.0)

    private fun play(p: Player, level: EchoLevel, pattern: List<Int>, rng: Random): List<Double> {
        val clicks = ArrayList<Double>()
        val over = (pattern.size - p.span).coerceAtLeast(0)
        val forget = if (pattern.isEmpty()) 0.0 else min(0.9, over * 0.35 / pattern.size * 3)
        for (s in pattern) {
            if (rng.nextDouble() < forget) continue
            val sd = p.jitter * (if (s % 2 == 1) p.offbeatPenalty else 1.0) * (if (level.guide) 1.0 else 1.6)
            clicks.add(s * level.slotMs + p.delay + rng.nextGaussian() * sd)
        }
        return clicks.sorted()
    }

    /** Mean cleared bars of a run, and the clear rate per level, over many runs. */
    private fun runs(p: Player, n: Int = 400, seed: Long = 7): Pair<Double, DoubleArray> {
        val rng = Random(seed)
        val tries = IntArray(60); val ok = IntArray(60)
        var total = 0
        repeat(n) {
            val judge = EchoJudge(); val run = EchoRun()
            while (!run.over && run.level < 59) {
                val lv = EchoLadder.level(run.level)
                val pat = EchoLadder.pattern(lv, rng)
                val r = judge.judge(lv, pat, play(p, lv, pat, rng))
                tries[lv.no]++; if (r.cleared) ok[lv.no]++
                run.record(r)
            }
            total += run.cleared
        }
        return total.toDouble() / n to DoubleArray(60) { if (tries[it] == 0) Double.NaN else ok[it].toDouble() / tries[it] }
    }

    @Test fun report() {
        for (p in listOf(novice, good, expert)) {
            val (mean, rate) = runs(p)
            println("ECHO ${p.name}: mean bars cleared %.1f; clear rate at level 1/5/10/15/20/30: %s".format(mean,
                listOf(1, 5, 10, 15, 20, 30).joinToString(" ") { if (rate[it].isNaN()) "-" else "%.2f".format(rate[it]) }))
        }
    }

    @Test fun easyToLearn() {
        val rate = runs(novice).second
        assertTrue("novice clears level 1 at ${rate[1]}", rate[1] >= 0.95)
        assertTrue("novice clears level 2 at ${rate[2]}", rate[2] >= 0.9)
    }

    @Test fun hardToMaster() {
        val n = runs(novice).first; val g = runs(good).first; val e = runs(expert).first
        assertTrue("ordered: novice $n < good $g < expert $e", n + 3 < g && g + 5 < e)
        assertTrue("novice should not run forever: $n", n in 4.0..20.0)
        assertTrue("the ladder must still beat an expert: $e", e in 15.0..45.0)
    }

    @Test fun noCheapStrategy() {
        // Clicking every slot, or clicking nothing, never gets past the first levels.
        val rng = Random(3)
        for (strategy in listOf("all", "none", "downbeats")) {
            var best = 0
            repeat(200) {
                val judge = EchoJudge(); val run = EchoRun()
                while (!run.over && run.level < 40) {
                    val lv = EchoLadder.level(run.level); val pat = EchoLadder.pattern(lv, rng)
                    val clicks = when (strategy) {
                        "all" -> (0 until lv.slots).map { it * lv.slotMs }
                        "downbeats" -> (0 until lv.slots step 2).map { it * lv.slotMs }
                        else -> emptyList()
                    }
                    run.record(judge.judge(lv, pat, clicks))
                }
                best = maxOf(best, run.cleared)
            }
            assertTrue("$strategy reached $best", best <= 6)
        }
    }

    @Test fun ladderGetsHarder() {
        for (n in 1..58) {
            val a = EchoLadder.level(n); val b = EchoLadder.level(n + 1)
            assertTrue("level ${n + 1} is not easier", b.hits >= a.hits && b.bpm >= a.bpm && b.offbeat >= a.offbeat && b.bars >= a.bars)
        }
    }

    @Test fun patternsAreValid() {
        val rng = Random(1)
        for (n in 1..60) {
            val lv = EchoLadder.level(n); val p = EchoLadder.pattern(lv, rng)
            assertTrue("level $n: ${p.size} hits, want ${lv.hits}", p.size == lv.hits && p == p.sorted() && p.distinct() == p && p.all { it in 0 until lv.slots })
        }
    }
}
