package com.faka.invisible.tell

/** Shared types of the TELL duel (see GAME-TELL.md at the repo root). Keep this file small and stable. */
enum class Move { JAB, GUARD, CHARGE }
enum class Side { ME, OPP }

/** The whole public state of a duel. Energy 0..5, life 0..4. */
data class State(val myE: Int, val oppE: Int, val myL: Int, val oppL: Int)

/** What happened in one round, from the point of view of ME. */
data class Round(
    val no: Int,                 // 1-based
    val before: State,
    val mine: Move,              // the move actually played (an unaffordable move is already GUARD)
    val theirs: Move,
    val after: State,
    val damageToMe: Int,
    val damageToOpp: Int,
    val myParry: Boolean,        // my guard beat their jab
    val theirParry: Boolean,
    val myChargeFizzled: Boolean,    // their jab beat my charge
    val theirChargeFizzled: Boolean,
)

enum class Result { WIN, LOSS, DRAW }

/** Sound cues the game asks the mixer for. `side` is where the sound sits: ME = centre, OPP = hard left. */
enum class Cue {
    TAP, DETENT_1, DETENT_2, STAMP, SNARE_CLOSE, KICK_ROLL,
    JAB, GUARD, CHARGE,
    PARRY_CLANG, COIN, FIZZLE, CRACK_BOOM, HIT, NOTHING,
    WIN, LOSS, DRAW, FORFEIT,
}
