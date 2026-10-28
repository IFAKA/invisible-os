package com.faka.invisible

import com.faka.invisible.logickit.LoopLevel
import org.junit.Assert.*
import org.junit.Test

class LoopLevelTest {
    @Test fun startsAtOneEarNormalSpeechSixHundred() {
        val l = LoopLevel()
        assertEquals(600, l.pace.itemMs); assertEquals(1.0, l.pace.speed, 0.0); assertEquals(1, l.pace.ears)
        assertEquals("level=0 item=600 speed=1.0 ears=1", l.describe())
    }

    @Test fun fiveCleanPicksRaiseOneLevel() {
        val saved = ArrayList<Int>(); val l = LoopLevel(save = { saved.add(it) })
        repeat(4) { assertFalse(l.chose(false)) }
        assertTrue(l.chose(false)); assertEquals(1, l.level); assertEquals(500, l.pace.itemMs); assertEquals(listOf(1), saved)
    }

    @Test fun theStepsGoSlowerItemsThenFasterSpeechThenTwoEars() {
        val l = LoopLevel()
        val seen = ArrayList<String>(); seen.add(l.describe())
        repeat(7 * 5) { if (l.chose(false)) seen.add(l.describe()) }
        assertEquals(listOf(
            "level=0 item=600 speed=1.0 ears=1", "level=1 item=500 speed=1.0 ears=1", "level=2 item=420 speed=1.0 ears=1", "level=3 item=350 speed=1.0 ears=1",
            "level=4 item=350 speed=1.25 ears=1", "level=5 item=350 speed=1.5 ears=1", "level=6 item=350 speed=1.75 ears=1", "level=7 item=350 speed=1.75 ears=2"), seen)
        repeat(20) { l.chose(false) }
        assertEquals("the top stays", LoopLevel.TOP, l.level)
    }

    @Test fun anUndoBreaksTheRunOfCleanPicks() {
        val l = LoopLevel()
        repeat(4) { l.chose(false) }
        l.chose(true)            // one Undo: the run starts again, the level stays
        repeat(4) { assertFalse(l.chose(false)) }
        assertEquals(0, l.level)
        assertTrue(l.chose(false)); assertEquals(1, l.level)
    }

    @Test fun twoUndosInARowStepBackOneLevelAndNotBelowZero() {
        val l = LoopLevel(start = 3)
        l.chose(true); assertEquals(3, l.level)
        l.chose(false); l.chose(true); assertEquals("a pick between the Undos is not a row", 3, l.level)
        assertTrue(l.chose(true)); assertEquals(2, l.level)
        val z = LoopLevel(start = 0); z.chose(true); assertFalse(z.chose(true)); assertEquals(0, z.level)
    }

    @Test fun theSavedLevelComesBack() {
        assertEquals(5, LoopLevel(start = 5).level)
        assertEquals(LoopLevel.TOP, LoopLevel(start = 99).level); assertEquals(0, LoopLevel(start = -3).level)
    }
}
