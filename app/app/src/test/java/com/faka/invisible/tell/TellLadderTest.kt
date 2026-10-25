package com.faka.invisible.tell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Measured (200 matches, draw = half): reader vs rung1 0.75, rung2 0.69, rung5 0.52; always jab .30, guard .41, charge-else-jab .27, charge-else-guard .52 vs rung 3. Rung 1 band passes only just. */
class TellLadderTest {
    private val solver get() = TellSolverTest.solver
    private val N = 200

    /** A player: picks a move from its own state, then sees the opponent's move. */
    private interface Player {
        fun choose(round: Int, s: State, rng: Random): Move
        fun see(theirs: Move) {}
    }

    /** Estimates the opponent's move frequencies (decayed) and best-responds using solver values. */
    private inner class Reader(val decay: Double) : Player {
        private val c = doubleArrayOf(1.0, 1.0, 1.0)
        override fun choose(round: Int, s: State, rng: Random): Move {
            val theirLegal = TellRules.legal(s.oppE)
            val z = theirLegal.sumOf { c[it.ordinal] }
            var best = Move.GUARD
            var bv = -1.0
            for (m in TellRules.legal(s.myE)) {
                var v = 0.0
                for (t in theirLegal) v += c[t.ordinal] / z * solver.value(round + 1, TellRules.resolve(s, m, t).after)
                if (v > bv + 1e-12) { bv = v; best = m }
            }
            return best
        }
        override fun see(theirs: Move) {
            for (i in c.indices) c[i] *= decay
            c[theirs.ordinal] += 1.0
        }
    }

    private class Always(val pref: List<Move>) : Player {
        override fun choose(round: Int, s: State, rng: Random): Move = pref.first { TellRules.cost(it) <= s.myE }
    }

    private fun duel(p: Player, o: Opponent, rng: Random): Result {
        var s = TellRules.start()
        var round = 1
        while (true) {
            val theirs = o.choose(round, TellRules.swap(s), rng)
            val mine = p.choose(round, s, rng)
            val r = TellRules.resolve(s, mine, theirs, round)
            o.observe(r.mine, round)
            p.see(r.theirs)
            s = r.after
            TellRules.finished(s, round)?.let { return it }
            round++
        }
    }

    /** Win share of the player over N matches (draw = half). */
    private fun share(rung: Int, seed: Long, mk: () -> Player): Double {
        val rng = Random(seed)
        var pts = 0.0
        for (i in 0 until N) {
            val res = duel(mk(), opponentForRung(rung, solver, seed * 1000 + i), rng)
            pts += when (res) { Result.WIN -> 1.0; Result.DRAW -> 0.5; Result.LOSS -> 0.0 }
        }
        return pts / N
    }

    @Test fun readerBeatsRung1() {
        val s = share(1, 11) { Reader(0.8) }
        println("LADDER reader vs rung 1: $s")
        assertTrue("reader vs rung 1 = $s", s >= 0.75)
    }

    @Test fun readerBeatsRung2() {
        val s = share(2, 12) { Reader(0.8) }
        println("LADDER reader vs rung 2: $s")
        assertTrue("reader vs rung 2 = $s", s >= 0.65)
    }

    @Test fun noAlwaysXBeatsRung3() {
        val policies = mapOf(
            "always jab" to listOf(Move.JAB, Move.GUARD),
            "always guard" to listOf(Move.GUARD),
            "charge else jab" to listOf(Move.CHARGE, Move.JAB, Move.GUARD),
            "charge else guard" to listOf(Move.CHARGE, Move.GUARD),
        )
        for ((name, pref) in policies) {
            val s = share(3, 13) { Always(pref) }
            println("LADDER $name vs rung 3: $s")
            assertTrue("$name vs rung 3 = $s", s <= 0.55)
        }
    }

    @Test fun rung5ReadsLessThanRung1() {
        val a = share(1, 14) { Reader(0.8) }
        val b = share(5, 15) { Reader(0.8) }
        println("LADDER reader vs rung 1: $a, vs rung 5: $b")
        assertTrue("rung1 $a rung5 $b", b < a)
    }

    @Test fun opponentsPlayLegalMoves() {
        val rng = Random(1)
        for (rung in 1..5) {
            val o = opponentForRung(rung, solver, 5)
            for (round in 1..20) {
                for (e in 0..5) {
                    val m = o.choose(round, State(e, 3, 4, 4), rng)
                    assertTrue("rung $rung energy $e $m", m in TellRules.legal(e))
                }
                o.observe(Move.values()[round % 3], round)
            }
        }
    }
}
