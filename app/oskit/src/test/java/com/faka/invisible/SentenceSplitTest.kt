package com.faka.invisible

import com.faka.invisible.voicekit.SentenceSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitTest {
    @Test fun splitsAtSentenceEnds() {
        assertEquals(listOf("Hello there my friend.", "How are you today? Fine!"), SentenceSplit.split("Hello there my friend. How are you today? Fine!"))   // "Fine!" is short: it joins the piece before it
    }
    @Test fun decimalAndDomainDoNotSplit() {
        assertEquals(listOf("The price is 3.5 dollars at shop.com today."), SentenceSplit.split("The price is 3.5 dollars at shop.com today."))
    }
    @Test fun shortPiecesJoinTheNext() {
        assertEquals(listOf("Hi. Come here now please."), SentenceSplit.split("Hi. Come here now please."))
    }
    @Test fun newlineSplitsAndBlankIsEmpty() {
        assertEquals(listOf("First line is long enough", "Second line is long too"), SentenceSplit.split("First line is long enough\nSecond line is long too"))
        assertTrue(SentenceSplit.split("  \n ... ").isEmpty())
    }
    @Test fun longTextIsCut() {
        val t = "word ".repeat(100).trim()
        val p = SentenceSplit.split(t)
        assertTrue(p.size >= 2 && p.all { it.length <= SentenceSplit.MAX })
        assertEquals(t, p.joinToString(" "))
    }
    @Test fun spanishMarks() {
        assertEquals(listOf("¿Cómo estás hoy? Muy bien gracias."), SentenceSplit.split("¿Cómo estás hoy? Muy bien gracias.").let { listOf(it.joinToString(" ")) })
    }
    @Test fun shortWordsAreLowerCase() {
        assertEquals("go", SentenceSplit.ear("Go")); assertEquals("no.", SentenceSplit.ear("No.")); assertEquals("Gold", SentenceSplit.ear("Gold"))
    }
    @Test fun longSentenceBreaksAtComma() {
        val t = "When the timer ends, the phone will play a long sound and then read your next task out loud to you."
        assertEquals(listOf("When the timer ends,", "the phone will play a long sound and then read your next task out loud to you."), SentenceSplit.split(t))
    }
}
