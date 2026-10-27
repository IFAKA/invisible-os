@file:OptIn(KitOnly::class)

package com.faka.invisible.tell

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Duel. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object TellVoice {
    /** The menu labels. */
    val TITLE get() = f("Duel")
    val HOW_TO_PLAY get() = f("How to play")
    // ---- markers inside a step's lines: a sound instead of a sentence ----
    val CLICK1 get() = f("<click1>")
    val CLICK2 get() = f("<click2>")
    val NOTES get() = f("<notes>")

    // ---- the levels: short teaching before each real match ----
    val L1_TAP get() = listOf(f("Level one. Jab and guard."), f("To leave, hold three seconds."), f("Tap to jab."))
    val L1_GUARD get() = listOf(f("Listen for a click."), CLICK1, f("Hold to the click to guard."))
    val L1_RULE get() = listOf(f("Guard beats jab."))
    val L2_CHARGE get() = listOf(f("Level two. Charge."), f("Hold longer."), f("Two clicks."), CLICK2, f("Let go after the second."))
    val L2_RULE get() = listOf(f("Charge beats guard."), f("Jab beats charge."), f("No charge twice in a row."))
    val L3_ENERGY get() = listOf(f("Level three. Energy."), f("Charge costs three."), f("Listen."), NOTES, f("More energy, higher note."), f("You get one back each round."))
    val DONE get() = listOf(f("Jab."), f("Guard."), f("Charge."))
    val NOT_AGAIN get() = f("Not again.")
    val SKIP get() = f("Let's move on.")
    val LEAVE_HINT get() = f("Hold three seconds to leave.")
    fun hint(tapStep: Boolean) = f(if (tapStep) "Press to continue." else "Press the button.")
    private val NAMES = listOf("jab", "guard", "charge")
    /** Every sentence of the practice (the markers are sounds). Each is at most 7 words. */
    val ALL: List<Phrase> = (L1_TAP + L1_GUARD + L1_RULE + L2_CHARGE + L2_RULE + L3_ENERGY +
        DONE + listOf(SKIP, LEAVE_HINT, hint(true), hint(false), NOT_AGAIN, f("Last life."), f("Their last life.")) +
        (0..2).flatMap { w -> (0..2).map { l -> wrong(l, w) } }.flatMap { it }).filter { it != CLICK1 && it != CLICK2 && it != NOTES }

    /** Feedback for a wrong hold: what it was, then what to do. Two sentences, spoken with a pause between. */
    fun wrong(level: Int, want: Int): List<Phrase> = listOf(f("That was a ${NAMES[level]}."), f(when (want) {
        0 -> "Tap quickly."; 1 -> "Hold for the first click."; else -> "Wait for the second click."
    }))

    private val NUM = listOf("zero", "one", "two", "three", "four", "five")
    fun duelRung(rung: Int) = f("Duel. Rung ${NUM[rung.coerceIn(0, 5)]}.")
    val LOADING get() = f("Loading the duel")
    val COULD_NOT_START get() = f("Duel could not start")

    private fun base(m: Move) = when (m) { Move.JAB -> "jab"; Move.GUARD -> "guard"; Move.CHARGE -> "charge" }
    private fun n(x: Int) = NUM[x.coerceIn(0, 5)]

    /** The result of one round, at most 5 words. */
    fun result(r: Round): Phrase {
        if (r.mine == r.theirs) return f("Both ${base(r.mine)}. " + if (r.damageToMe > 0) "Both hurt." else "Nothing.")
        val tail = when {
            r.myParry -> "Parry."
            r.theirParry -> "Parried."
            r.myChargeFizzled -> "Fizzle."
            r.theirChargeFizzled -> "Fizzle."
            r.damageToOpp > 0 -> "Hit."
            r.damageToMe > 0 -> "Ouch."
            else -> "Nothing."
        }
        return f("They ${base(r.theirs)}. You ${base(r.mine)}. $tail")
    }

    /** Level 1 (jab and guard): one word, and a warning at the last life. Empty = say nothing. */
    fun narrationL1(r: Round): Phrase {
        val sb = StringBuilder(when {
            r.myParry -> "Parry."
            r.theirParry -> "Ouch."
            r.mine == r.theirs && r.damageToMe > 0 -> "Trade."
            else -> ""
        })
        if (r.after.myL == 1 && r.before.myL > 1) sb.append(" Last life.")
        if (r.after.oppL == 1 && r.before.oppL > 1) sb.append(" Their last life.")
        return f(sb.toString().trim())
    }

    /** Level 2 (charge): one to three words. */
    fun narrationL2(r: Round): Phrase {
        val big = Move.CHARGE
        val sb = StringBuilder(when {
            r.myParry -> "Parry."
            r.theirParry -> "Ouch."
            r.myChargeFizzled -> "Charge broken."
            r.theirChargeFizzled -> "Their charge broken."
            r.mine == big && r.damageToOpp > 0 && r.damageToMe > 0 -> "Both hurt."
            r.mine == big && r.damageToOpp > 0 -> "Big hit."
            r.theirs == big && r.damageToMe > 0 -> "Big ouch."
            r.mine == r.theirs && r.damageToMe > 0 -> "Trade."
            else -> ""
        })
        if (r.after.myL == 1 && r.before.myL > 1) sb.append(" Last life.")
        return f(sb.toString().trim())
    }

    /** The coached narration (level 3, the full game): the result, a life change only when life changed, and two energy remarks. */
    fun narration(r: Round, lowEnergy: Boolean = false, chargeReady: Boolean = false): Phrase {
        val sb = StringBuilder(result(r).text)
        if (r.damageToMe > 0) sb.append(" Your life ").append(n(r.after.myL)).append('.')
        if (r.damageToOpp > 0) sb.append(" Their life ").append(n(r.after.oppL)).append('.')
        if (chargeReady) sb.append(" Charge ready.") else if (lowEnergy) sb.append(" Low energy.")
        return f(sb.toString())
    }

    // ---- the end of a match ----
    val WIN get() = f("You win.")
    val LOSE get() = f("You lose.")
    val DRAW get() = f("Draw.")
    val PLAY_AGAIN get() = f("Tap to play again. Hold to leave.")
    /** After a match: the result word, what the mistake cost (content computed by the game), and the way on. Empty parts are left out. */
    fun matchEnd(word: Phrase, mistake: Phrase): Phrase =
        Phrase.join(listOf(word, mistake, PLAY_AGAIN).filter { it.isNotEmpty })
    /** "Not again." in front of a narration. */
    fun notAgainThen(text: Phrase): Phrase = Phrase.join(listOf(NOT_AGAIN, text).filter { it.isNotEmpty })
    /** Three taps: the score and your moves. */
    fun summary(rounds: Int, myLife: Int?, oppLife: Int?, jabs: Int, guards: Int, charges: Int): Phrase {
        val life = if (myLife != null && oppLife != null) " Life $myLife to $oppLife." else ""
        return f("$rounds rounds.$life Jab $jabs, guard $guards, charge $charges.")
    }
}
