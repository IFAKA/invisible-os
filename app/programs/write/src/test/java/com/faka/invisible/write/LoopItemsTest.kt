package com.faka.invisible.write

import com.faka.invisible.voicekit.Pacing
import org.junit.Assert.*
import org.junit.Test

class LoopItemsTest {
    private val words = listOf("hello" to -1.0, "help" to -1.5, "held" to -2.0, "hell" to -2.5)
    private fun build(phrases: List<String>, chances: List<Double> = emptyList(), prefix: String = "") =
        LoopItems.build(words, emptyList(), prefix, false, phrases, false, false, { LetterBigrams(words.map { it.first }) }, chances)

    @Test fun atMostThreeGuessesAreReadAloneAndEverythingElseIsShort() {
        val items = build(listOf("how are you", "what is up"), listOf(0.3, 0.2))
        val guesses = items.filter { it.guess }
        assertEquals(Pacing.LOOP_GUESS_MAX, guesses.size)
        assertTrue("guesses come first", items.take(3).all { it.guess })
        assertTrue(items.drop(3).none { it.guess })
        assertTrue("letters are single letters or short controls", items.filter { !it.guess }.all { it.say.text.length <= 6 })
        assertEquals(setOf("p:0", "p:1"), guesses.map { it.key }.filter { it.startsWith("p:") }.toSet())   // the phrase keys still index the offered texts
    }

    @Test fun withoutTextsTheLikelyWordsAreTheGuesses() {
        val items = build(emptyList())
        assertEquals(listOf("w:hello", "w:help", "w:held"), items.filter { it.guess }.map { it.key })
    }
}
