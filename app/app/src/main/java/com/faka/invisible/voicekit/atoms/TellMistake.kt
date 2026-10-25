@file:OptIn(KitOnly::class)

package com.faka.invisible.voicekit

import com.faka.invisible.tell.Move
import com.faka.invisible.tell.Round
import com.faka.invisible.tell.TellRules

private fun verb(m: Move) = when (m) { Move.JAB -> "jabbed"; Move.GUARD -> "guarded"; Move.CHARGE -> "charged" }

private fun score(r: Round): Double =
    (r.damageToOpp - r.damageToMe) + 0.5 * (r.after.myE - r.before.myE)

private fun lives(n: Int) = (when (n) { 1 -> "one"; 2 -> "two"; 3 -> "three"; else -> n.toString() }) + if (n == 1) " life" else " lives"

/** The round where a different move would have scored best against the opponent's actual move. */
fun costliestMistake(rounds: List<Round>): Phrase {
    if (rounds.isEmpty()) return Phrase.fixed("")
    var worst: Round? = null
    var worstGap = 0.0
    var alt: Round? = null
    for (r in rounds) {
        val actual = score(r)
        for (m in TellRules.legal(r.before.myE)) {
            val a = TellRules.resolve(r.before, m, r.theirs, r.no)
            val gap = score(a) - actual
            if (gap > worstGap + 1e-9) { worstGap = gap; worst = r; alt = a }
        }
    }
    val sb = StringBuilder()
    val w = worst
    val a = alt
    if (w != null && a != null) {
        val saved = w.damageToMe - a.damageToMe
        val missed = a.damageToOpp - w.damageToOpp
        val cost = when {
            saved > 0 -> "it cost you ${lives(saved)}"
            missed > 0 -> "you missed ${missed} damage"
            else -> "it cost you energy"
        }
        sb.append("In round ${w.no} you ${verb(w.mine)}, they ${verb(w.theirs)}, and $cost.")
    }
    val counts = Move.values().associateWith { m -> rounds.count { it.mine == m } }
    val top = counts.maxByOrNull { it.value }!!
    val pct = Math.round(100.0 * top.value / rounds.size).toInt()
    if (top.value * 100 >= 55 * rounds.size) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append("You ${verb(top.key)} $pct percent.")
    }
    return Phrase.fixed(sb.toString())
}
