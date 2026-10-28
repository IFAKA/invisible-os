package com.faka.invisible.logickit

import org.junit.Assert.*
import org.junit.Test

class CompletionTest {
    @Test fun lettersAreTheStartOfTheWordOrTheFirstLettersOfTheWords() {
        assertTrue(Completion.fits("how are you", "hay"))
        assertTrue(Completion.fits("hayden", "hay"))
        assertTrue(Completion.fits("meet me tomorrow", "meet me t"))
        assertTrue(Completion.fits("Meet me tomorrow", "mmt"))
        assertFalse(Completion.fits("what is up", "hay"))
        assertFalse(Completion.fits("meet you tomorrow", "meet me t"))
        assertFalse(Completion.fits("anything", ""))
    }

    @Test fun theLettersCopiedAsAWordDoNotFit() {
        assertTrue(Completion.fits("how are you", emptyList(), "hay"))
        assertTrue(Completion.fits("see you tomorrow", listOf("see", "you"), "tom"))
        assertFalse(Completion.fits("hay how are you", emptyList(), "hay"))
        assertFalse(Completion.fits("billie j help?", listOf("billie"), "j"))
        assertFalse(Completion.fits("hay fever", emptyList(), "hay"))
        assertTrue(Completion.fits("hay fever", emptyList(), "hay", wholeWord = true))
        assertFalse(Completion.fits("thanks you tom", listOf("thank"), "y"))
    }
}
