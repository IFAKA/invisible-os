package com.faka.invisible.tell

/** Pure rules of the TELL duel. Must give the same results as yomi.py `resolve`. */
object TellRules {
    const val LIFE = 4
    const val ENERGY_START = 3
    const val ENERGY_CAP = 5
    const val ROUNDS = 20

    fun cost(m: Move): Int = when (m) { Move.JAB -> 1; Move.GUARD -> 0; Move.CHARGE -> 3 }

    /** Moves you can pay for, in the order JAB, GUARD, CHARGE. */
    fun legal(energy: Int): List<Move> = Move.values().filter { cost(it) <= energy }

    /** A move you cannot pay for becomes GUARD. */
    fun fix(move: Move, energy: Int): Move = if (cost(move) <= energy) move else Move.GUARD

    fun start(): State = State(ENERGY_START, ENERGY_START, LIFE, LIFE)

    /** Swap the point of view. */
    fun swap(s: State): State = State(s.oppE, s.myE, s.oppL, s.myL)

    /** Play one round (1-based `no`) from ME's point of view. Unaffordable moves are fixed to GUARD first. */
    fun resolve(state: State, mine: Move, theirs: Move, no: Int = 1): Round {
        val m = fix(mine, state.myE)
        val t = fix(theirs, state.oppE)
        var me = state.myE - cost(m)
        var oe = state.oppE - cost(t)
        var dMe = 0
        var dOpp = 0
        var myParry = false
        var theirParry = false
        var myFizz = false
        var theirFizz = false
        if (m == t) {
            if (m == Move.JAB) { dMe = 1; dOpp = 1 }
            if (m == Move.CHARGE) { dMe = 2; dOpp = 2 }
        } else if (beats(m, t)) {
            when (m) {
                Move.GUARD -> { me += 1; dOpp = 1; myParry = true }
                Move.JAB -> { dOpp = 1; theirFizz = true }
                Move.CHARGE -> dOpp = 2
            }
        } else {
            when (t) {
                Move.GUARD -> { oe += 1; dMe = 1; theirParry = true }
                Move.JAB -> { dMe = 1; myFizz = true }
                Move.CHARGE -> dMe = 2
            }
        }
        val after = State(
            minOf(ENERGY_CAP, me + 1), minOf(ENERGY_CAP, oe + 1),
            state.myL - dMe, state.oppL - dOpp,
        )
        return Round(no, state, m, t, after, dMe, dOpp, myParry, theirParry, myFizz, theirFizz)
    }

    private fun beats(a: Move, b: Move) =
        (a == Move.GUARD && b == Move.JAB) || (a == Move.JAB && b == Move.CHARGE) || (a == Move.CHARGE && b == Move.GUARD)

    /** Result for ME if the match is over after `round` rounds were played, else null. */
    fun finished(state: State, round: Int): Result? {
        if (state.myL <= 0 || state.oppL <= 0 || round >= ROUNDS) {
            return when {
                state.myL > state.oppL -> Result.WIN
                state.myL < state.oppL -> Result.LOSS
                else -> Result.DRAW
            }
        }
        return null
    }
}
