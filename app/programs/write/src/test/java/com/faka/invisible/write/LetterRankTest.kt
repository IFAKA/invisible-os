package com.faka.invisible.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoothed letter chances: the word model plus the letter pairs, never a fixed tiny floor. */
class LetterRankTest {
    // "bile" is the only word that starts with "bil"; no word starts with "bill"
    private val words = listOf("bile" to -8.0, "will" to -3.0, "tell" to -4.0, "all" to -2.0, "hello" to -5.0, "small" to -6.0, "still" to -6.5, "fall" to -7.0, "well" to -7.5)
    private val grams = LetterBigrams(words.map { it.first })

    @Test fun afterBilTheLetterLIsInTheFirstSixEvenWhenNoWordStartsWithBill() {
        val top = LetterRank.letters(words, "bil") { grams }.take(6).map { it.first }
        assertTrue("l in $top", 'l' in top)
        assertEquals('e', top.first())   // the word model still leads
    }

    @Test fun theChancesSumToOneAndNoLetterWithPairsIsNearZero() {
        val r = LetterRank.letters(words, "bil") { grams }
        assertEquals(1.0, r.sumOf { it.second }, 1e-9)
        assertTrue(r.first { it.first == 'l' }.second > 0.05)
    }

    @Test fun anEmptyPrefixUsesTheFirstLettersOfWords() {
        val top = LetterRank.letters(words, "") { grams }.map { it.first }
        assertTrue(top.indexOf('w') < top.indexOf('q'))
    }
}
