package com.faka.invisible.now

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowCoreTest {
    @Test fun factsFollowPermissions() {
        assertEquals(listOf(NowCore.Fact.TIME, NowCore.Fact.BATTERY), NowCore.facts(calendar = false, location = false))
        assertEquals(listOf(NowCore.Fact.TIME, NowCore.Fact.BATTERY, NowCore.Fact.EVENT), NowCore.facts(true, false))
        assertEquals(listOf(NowCore.Fact.TIME, NowCore.Fact.BATTERY, NowCore.Fact.WEATHER), NowCore.facts(false, true))
        assertEquals(4, NowCore.facts(true, true).size)
    }
    @Test fun nextAndBack() {
        assertEquals(1, NowCore.next(0, 3)); assertNull(NowCore.next(2, 3))
        assertEquals(0, NowCore.back(1)); assertNull(NowCore.back(0))
    }
    @Test fun volume() {
        assertEquals(47, SwitchesCore.percent(7, 15)); assertEquals(100, SwitchesCore.percent(15, 15)); assertEquals(0, SwitchesCore.percent(3, 0))
        assertEquals(15, SwitchesCore.stepped(15, 15, true)); assertEquals(0, SwitchesCore.stepped(0, 15, false)); assertEquals(8, SwitchesCore.stepped(7, 15, true))
    }
    @Test fun dnd() { assertEquals(true, SwitchesCore.dndNowOn(true)); assertEquals(false, SwitchesCore.dndNowOn(false)) }
    @Test fun sevenSwitches() { assertEquals(7, SwitchesCore.ITEMS.size) }
}
