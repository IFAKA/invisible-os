package com.faka.invisible

import com.faka.invisible.logickit.ReactionGap
import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionGapTest {
    @Test fun fewSamplesGiveTheUserSetting() {
        assertEquals(1600L, ReactionGap(List(7) { 100L }).gap(1600))
    }

    @Test fun fastUserGetsP90PlusMargin() {
        val g = ReactionGap(List(10) { 400L + it * 10 })   // p90 = 490
        assertEquals(680L, g.gap(1600))
    }

    @Test fun neverBelowTheFloorNeverAboveTheSetting() {
        assertEquals(150L, ReactionGap(List(20) { -500L }).gap(1600))
        assertEquals(1600L, ReactionGap(List(20) { 5000L }).gap(1600))
        assertEquals(600L, ReactionGap(List(20) { 5000L }).gap(600))   // a setting under the floor wins
    }

    @Test fun keepsOnlyTheLast40() {
        val g = ReactionGap()
        repeat(40) { g.add(5000) }
        repeat(40) { g.add(100) }
        assertEquals(40, g.size)
        assertEquals(300L, g.gap(1600))
    }

    @Test fun savesAndLoads() {
        val g = ReactionGap(listOf(1, -2, 300))
        assertEquals(listOf(1L, -2L, 300L), ReactionGap.decode(g.encode()))
        assertEquals(emptyList<Long>(), ReactionGap.decode(""))
    }
}
