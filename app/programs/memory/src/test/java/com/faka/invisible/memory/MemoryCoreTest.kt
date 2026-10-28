package com.faka.invisible.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryCoreTest {
    @Test fun clicksMapToTones() {
        assertEquals(0, MemoryCore.symbolOf(1)); assertEquals(1, MemoryCore.symbolOf(2))
        assertNull(MemoryCore.symbolOf(0)); assertNull(MemoryCore.symbolOf(3))
    }

    @Test fun sequenceGrowsByOneAndStartsLowHigh() {
        val c = MemoryCore(java.util.Random(1))
        assertEquals(listOf(0), c.newRound())
        assertEquals(MemoryCore.Step.ROUND_DONE, c.answer(0))
        assertEquals(listOf(0, 1), c.newRound())
        c.answer(0); c.answer(1)
        assertEquals(3, c.newRound().size)
    }

    @Test fun copyingEveryRoundKeepsScoring() {
        val c = MemoryCore(java.util.Random(7))
        for (round in 1..10) {
            val seq = c.newRound()
            assertEquals(round, seq.size)
            seq.dropLast(1).forEach { assertEquals(MemoryCore.Step.RIGHT, c.answer(it)) }
            assertEquals(MemoryCore.Step.ROUND_DONE, c.answer(seq.last()))
            assertEquals(round, c.score)
        }
    }

    @Test fun aWrongToneOrBadClickCountEndsIt() {
        val c = MemoryCore(java.util.Random(3))
        c.newRound(); assertEquals(MemoryCore.Step.WRONG, c.answer(1))
        val d = MemoryCore(java.util.Random(3))
        d.newRound(); assertEquals(MemoryCore.Step.WRONG, d.answer(MemoryCore.symbolOf(3)))
        assertEquals(0, d.score)
    }
}
