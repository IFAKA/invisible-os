package com.faka.invisible.reaction

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ReactionCoreTest {
    @Test fun waitStaysInRangeAndVaries() {
        val c = ReactionCore(Random(5))
        val w = (1..200).map { c.waitMs() }
        assertTrue(w.all { it in ReactionCore.MIN_WAIT_MS until ReactionCore.MAX_WAIT_MS })
        assertTrue(w.toSet().size > 50)
    }

    @Test fun averageOfFiveRoundsThenDone() {
        val c = ReactionCore(Random(1))
        assertEquals(0, c.average())
        listOf(300, 250, 350, 400, 200).forEachIndexed { i, ms -> assertFalse(c.done); c.record(ms); assertEquals(i + 1, c.times.size) }
        assertTrue(c.done)
        assertEquals(300, c.average())
    }

    @Test fun faultsAreCountedAndSlowRoundsAreCapped() {
        val c = ReactionCore(Random(1))
        c.fault(); c.fault()
        assertEquals(2, c.faults)
        c.record(99999)
        assertEquals(ReactionCore.SLOW_MS, c.times[0])
        assertEquals(0, c.times.size - 1)
    }
}
