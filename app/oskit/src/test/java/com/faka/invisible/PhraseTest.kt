package com.faka.invisible

import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

class PhraseTest {
    @Test fun contentIsNotFixed() {
        val p = Phrase.content("hola", "es")
        assertEquals("hola", p.text); assertEquals("es", p.lang); assertFalse(p.isFixed)
    }

    @Test fun fixedSentencesComeFromTheKit() {
        assertTrue(Voice.NO_EVENTS.isFixed)
    }

    @Test fun equalByTextAndLanguage() {
        assertEquals(Phrase.content("a", "en"), Phrase.content("a", "en"))
        assertNotEquals(Phrase.content("a", "en"), Phrase.content("a", "es"))
        assertEquals("a", Phrase.content("a").toString())
    }

    @Test fun joinAndPlus() {
        val j = Phrase.join(listOf(Phrase.content("a"), Phrase.content("b", "es")))
        assertEquals("a b", j.text); assertEquals("es", j.lang)
        assertEquals("ab", (Phrase.content("a") + Phrase.content("b")).text)
    }

    @Test fun pacingFollowsTheRule() {
        assertEquals(600L + 250L * 2, Pacing.pauseAfter("one two"))
        assertEquals(3, Pacing.words(Phrase.content("one two three")))
        assertTrue(Pacing.HOLD_MS < Pacing.LEAVE_MS)
    }
}
