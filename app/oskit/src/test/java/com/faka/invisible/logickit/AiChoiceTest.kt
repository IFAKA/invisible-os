package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Test

class AiChoiceTest {
    @Test fun switchOffMeansNoAiAnywhere() {
        for (c in listOf(true, false)) for (d in listOf(true, false)) for (p in listOf(true, false))
            assertEquals(AiSource.NONE, AiChoice.pick(false, c, d, p))
    }

    @Test fun computerFirstThenPhoneThenNone() {
        assertEquals(AiSource.COMPUTER, AiChoice.pick(true, true, false, true))
        assertEquals(AiSource.COMPUTER, AiChoice.pick(true, true, false, false))
        assertEquals("a computer that is down is skipped", AiSource.PHONE, AiChoice.pick(true, true, true, true))
        assertEquals(AiSource.PHONE, AiChoice.pick(true, false, false, true))
        assertEquals(AiSource.NONE, AiChoice.pick(true, true, true, false))
        assertEquals(AiSource.NONE, AiChoice.pick(true, false, false, false))
    }

    @Test fun labelsSayWhichAiIsInUse() {
        assertEquals("Off", AiChoice.label(false, true, true))
        assertEquals("Computer", AiChoice.label(true, true, false))
        assertEquals("Phone", AiChoice.label(true, false, true))
        assertEquals("Phone, model not downloaded", AiChoice.label(true, false, false))
    }
}
