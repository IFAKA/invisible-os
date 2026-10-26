package com.faka.invisible.tell

/**
 * Backward DP of yomi.py `run("nash")`: 20 rounds, life 4, energy cap 5, mix grid 1/20.
 * `round` arguments are 1-based (like Round.no); value(21, s) is the final score.
 * Values are the win share (win 1, draw 0.5, loss 0) for the player whose state it is.
 */
class TellSolver {
    private val h = TellRules.ROUNDS
    private val lMax = TellRules.LIFE
    private val eN = TellRules.ENERGY_CAP + 1

    // index: [t][e1][e2][l1-1][l2-1]; t = 0..h
    private val value = Array(h + 1) { DoubleArray(eN * eN * lMax * lMax) }
    private val mixes = Array(h) { arrayOfNulls<DoubleArray>(eN * eN * lMax * lMax) }
    @Volatile private var solved = false
    val isSolved: Boolean get() = solved

    private fun idx(s: State) = ((s.myE * eN + s.oppE) * lMax + (s.myL - 1)) * lMax + (s.oppL - 1)

    private fun terminal(l1: Int, l2: Int, t: Int): Double? =
        if (l1 <= 0 || l2 <= 0 || t == h) (if (l1 > l2) 1.0 else if (l1 < l2) 0.0 else 0.5) else null

    @Synchronized
    fun solve() {
        if (solved) return
        val grids = mapOf(1 to grid(1), 2 to grid(2), 3 to grid(3))
        for (t in h - 1 downTo 0) {
            for (e1 in 0 until eN) for (e2 in 0 until eN) for (l1 in 1..lMax) for (l2 in 1..lMax) {
                val a1 = TellRules.legal(e1)
                val a2 = TellRules.legal(e2)
                val s = State(e1, e2, l1, l2)
                val m = Array(a1.size) { r ->
                    DoubleArray(a2.size) { c ->
                        val n = TellRules.resolve(s, a1[r], a2[c]).after
                        terminal(n.myL, n.oppL, t + 1) ?: value[t + 1][idx(n)]
                    }
                }
                var best = -1.0
                var bm: DoubleArray? = null
                for (p in grids.getValue(a1.size)) {
                    var v = Double.MAX_VALUE
                    for (c in a2.indices) {
                        var sum = 0.0
                        for (r in a1.indices) sum += p[r] * m[r][c]
                        if (sum < v) v = sum
                    }
                    if (v > best) { best = v; bm = p }
                }
                value[t][idx(s)] = best
                mixes[t][idx(s)] = bm
            }
        }
        solved = true
    }

    private fun grid(n: Int): List<DoubleArray> {
        val step = 20
        return when (n) {
            1 -> listOf(doubleArrayOf(1.0))
            2 -> (0..step).map { doubleArrayOf(it / step.toDouble(), 1 - it / step.toDouble()) }
            else -> buildList {
                for (i in 0..step) for (j in 0..step - i)
                    add(doubleArrayOf(i / step.toDouble(), j / step.toDouble(), 1 - (i + j) / step.toDouble()))
            }
        }
    }

    /** Value for the player whose state it is, at the start of `round` (1-based). */
    fun value(round: Int, state: State): Double {
        terminal(state.myL, state.oppL, round - 1)?.let { return it }
        solve()
        return value[round - 1][idx(state)]
    }

    /** Nash mix over the legal moves for the player whose state it is. Moves not legal are absent. */
    fun mix(round: Int, state: State): Map<Move, Double> {
        solve()
        val legal = TellRules.legal(state.myE)
        val t = (round - 1).coerceIn(0, h - 1)
        val p = mixes[t][idx(state)] ?: return legal.associateWith { 1.0 / legal.size }
        return legal.indices.associate { legal[it] to p[it] }
    }
}
