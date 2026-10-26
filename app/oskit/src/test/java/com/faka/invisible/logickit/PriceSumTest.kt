package com.faka.invisible.logickit

import org.junit.Assert.*
import org.junit.Test

class PriceSumTest {
    @Test fun addsPricesToATotal() {
        val s = PriceSum(); assertTrue(s.add("1250")); s.add("340")
        assertEquals("1590", s.spoken()); assertEquals(2, s.count)
    }

    @Test fun centsAreExact() { val s = PriceSum(); s.add("0.10"); s.add("0.20"); assertEquals("0 point 3", s.spoken()) }

    @Test fun decimalsAreSpokenAsPoint() { val s = PriceSum(); s.add("12.5"); assertEquals("12 point 5", s.spoken()) }

    @Test fun notANumberIsRefused() { val s = PriceSum(); assertFalse(s.add("")); assertFalse(s.add(".")); assertTrue(s.isEmpty) }

    @Test fun undoRemovesTheLastPrice() { val s = PriceSum(); s.add("5"); s.add("7"); s.undo(); assertEquals("5", s.spoken()) }
}
