package com.faka.invisible

import com.faka.invisible.logickit.ReactionGap
import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionGapTest {
    @Test fun fewSamplesGiveTheUserSetting() {
        assertEquals(1600L, ReactionGap(List(7) { 100L }).gap(1600))
    }

    @Test fun slowUserGetsP90PlusMargin() {
        val g = ReactionGap(List(10) { 1400L + it * 10 })   // p90 = 1480
        assertEquals(1680L, g.gap(1000))
    }

    @Test fun neverBelowTheSettingNeverAboveTheMax() {
        assertEquals(1600L, ReactionGap(List(20) { -500L }).gap(1600))   // a fast user lowers the slider
        assertEquals(3000L, ReactionGap(List(20) { 5000L }).gap(1600))
        assertEquals(3500L, ReactionGap(List(20) { 5000L }).gap(3500))   // a setting over the max wins
    }

    @Test fun keepsOnlyTheLast40() {
        val g = ReactionGap()
        repeat(40) { g.add(5000) }
        repeat(40) { g.add(100) }
        assertEquals(40, g.size)
        assertEquals(1600L, g.gap(1600))
        assertEquals(400L, g.gap(400))
    }

    @Test fun savesAndLoads() {
        val g = ReactionGap(listOf(1, -2, 300))
        assertEquals(listOf(1L, -2L, 300L), ReactionGap.decode(g.encode()))
        assertEquals(emptyList<Long>(), ReactionGap.decode(""))
    }
}
