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
}
