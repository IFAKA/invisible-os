@file:OptIn(KitOnly::class)

package com.faka.invisible.echo

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Echo. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

/** Spoken sentences of Echo. Plain English, at most 7 words per sentence, one idea each. Pacing is in the kit (Pacing.kt). */
object EchoVoice {
    /** The menu label. */
    val TITLE get() = f("Echo")
    val INTRO get() = f("Echo. Listen, then copy.")
    val LEAVE get() = f("Hold three seconds to leave.")
    val AGAIN get() = f("Again.")
    val LIFE_BACK get() = f("Life back.")
    private val WORDS = listOf("No", "One", "Two", "Three", "Four", "Five")
    fun lives(n: Int) = f(if (n == 1) "Last life." else "${WORDS[n.coerceIn(0, 5)]} lives.")
    val LOADING get() = f("One moment.")
    fun level(n: Int) = f("Level $n.")
    fun over(score: Int, best: Int) = f(if (score >= best && score > 0) "Game over. New best, $score." else "Game over. Score $score. Best $best.")
    val ALL: List<Phrase> get() = listOf(INTRO, LEAVE, AGAIN, LOADING, LIFE_BACK) + (1..5).map { lives(it) }
}
