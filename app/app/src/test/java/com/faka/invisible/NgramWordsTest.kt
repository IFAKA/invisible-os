package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class NgramWordsTest {
    private val m = NgramWords(sequenceOf(
        "U\tno\t50", "U\tde\t40", "U\tcasa\t5", "U\tvoy\t10", "U\tmañana\t3",
        "B\t<s>\tno\t9", "B\tvoy\tcasa\t7",
        "T\tno\tvoy\tmañana\t4",
    ))

    private fun top(c: List<String>) = m.ranked(c).first().first

    @Test fun startOfSentenceUsesFirstWords() = assertEquals("no", top(emptyList()))
    @Test fun bigramBeatsCommonWord() = assertEquals("casa", top(listOf("voy")))
    @Test fun trigramBeatsBigram() = assertEquals("mañana", top(listOf("no", "voy")))
    @Test fun everyWordIsReachable() = assertEquals(5, m.ranked(listOf("voy")).size)
    @Test fun spanishFirstPairKeepsShortWords() = assertFalse(m.skipShortFirst)
}
