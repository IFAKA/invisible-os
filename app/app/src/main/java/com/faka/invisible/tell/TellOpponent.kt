package com.faka.invisible.tell

import java.util.Random

/** An opponent. `state` is from the OPPONENT's view (myE is the opponent's own energy). */
interface Opponent {
    fun choose(round: Int, state: State, rng: Random): Move
    /** Tell the opponent what the player played in `round` (after the reveal). */
    fun observe(playerMove: Move, round: Int)
}

private fun draw(mix: Map<Move, Double>, legal: List<Move>, rng: Random): Move {
    var x = rng.nextDouble()
    var last = legal.last()
    for (m in legal) {
        val p = mix[m] ?: 0.0
        if (p <= 0.0) continue
        last = m
        x -= p
        if (x < 0) return m
    }
    return last
}

/** Rungs 1 and 2. `favourite(round)` is the move played 60% of the time (others 20% each). */
private class HabitBot(val favourite: (Int) -> Move) : Opponent {
    override fun choose(round: Int, state: State, rng: Random): Move {
        val fav = favourite(round)
        val mix = Move.values().associateWith { if (it == fav) 0.6 else 0.2 }
        val legal = TellRules.legal(state.myE)
        val z = legal.sumOf { mix.getValue(it) }
        return draw(legal.associateWith { mix.getValue(it) / z }, legal, rng)
    }
    override fun observe(playerMove: Move, round: Int) {}
}

private class NashBot(val solver: TellSolver) : Opponent {
    override fun choose(round: Int, state: State, rng: Random): Move =
        draw(solver.mix(round, state), TellRules.legal(state.myE), rng)
    override fun observe(playerMove: Move, round: Int) {}
}

/** Nash + exploit: learns the player's move frequencies given the last `order` player moves (decay `decay`). */
private class ExploitBot(
    val solver: TellSolver, val weight: Double, val order: Int, val decay: Double = 0.8,
) : Opponent {
    private val counts = HashMap<List<Move>, DoubleArray>()
    private val history = ArrayList<Move>()

    private fun ctx(): List<Move> = history.takeLast(order)

    override fun observe(playerMove: Move, round: Int) {
        val c = counts.getOrPut(ctx()) { DoubleArray(3) }
        for (i in c.indices) c[i] *= decay
        c[playerMove.ordinal] += 1.0
        history.add(playerMove)
    }

    override fun choose(round: Int, state: State, rng: Random): Move {
        val legal = TellRules.legal(state.myE)
        val c = counts[ctx()]
        if (c == null || rng.nextDouble() >= weight) return draw(solver.mix(round, state), legal, rng)
        // estimated player mix over the moves the player can pay for
        val theirLegal = TellRules.legal(state.oppE)
        val z = theirLegal.sumOf { c[it.ordinal] }
        if (z <= 1e-9) return draw(solver.mix(round, state), legal, rng)
        var best = legal.first()
        var bv = -1.0
        for (m in legal) {
            var v = 0.0
            for (pm in theirLegal) {
                // state is from the opponent's view; resolve(me = opponent)
                val next = TellRules.resolve(state, m, pm).after
                v += c[pm.ordinal] / z * solver.value(round + 1, next)
            }
            if (v > bv + 1e-12) { bv = v; best = m }
        }
        return best
    }
}

fun opponentForRung(rung: Int, solver: TellSolver, seed: Long): Opponent {
    val r = Random(seed)
    val moves = Move.values()
    return when (rung) {
        1 -> { val fav = moves[r.nextInt(3)]; HabitBot { fav } }
        2 -> { val off = r.nextInt(3); HabitBot { round -> moves[(off + (round - 1) / 6) % 3] } }
        3 -> NashBot(solver)
        4 -> ExploitBot(solver, 0.5, 1)
        else -> ExploitBot(solver, 0.85, 2)
    }
}
