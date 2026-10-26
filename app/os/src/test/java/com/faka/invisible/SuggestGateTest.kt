package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestGateTest {
    @Test fun anAppTwoLevelsDeepWithHalfTheShareIsOffered() {
        assertTrue(SuggestGate.worth(0.5, SuggestGate.treeCost(heard = 7, levels = 2)))
    }

    @Test fun anAppAlreadySecondAtHomeIsNeverOffered() {
        assertFalse(SuggestGate.worth(0.99, SuggestGate.treeCost(heard = 2, levels = 0)))
    }

    @Test fun aSmallShareOffersNothing() {
        assertFalse(SuggestGate.worth(0.05, SuggestGate.treeCost(heard = 9, levels = 2)))
    }

    @Test fun laterPositionsSaveLess() {
        assertTrue(SuggestGate.worth(0.4, 6.0, 1))
        assertFalse(SuggestGate.worth(0.2, 4.0, 3))
    }

    @Test fun treeCostCountsLevels() { assertEquals(8.0, SuggestGate.treeCost(7, 2), 1e-9) }

    @Test fun suggesterGateAndOthers() {
        val day = 86_400_000L; val now = 100 * day + 10 * 3_600_000L
        fun pick(i: Int, key: String) = Suggester.Pick(now - i * 1000L, key)
        val h = List(3) { pick(it, "app:wa") } + List(3) { pick(10 + it, "hub:home") }   // equal scores; the home entry competes
        assertEquals(listOf("app:wa"), Suggester.top(h, setOf("app:wa"), now, others = setOf("hub:home")))
        assertEquals(emptyList<String>(), Suggester.top(h, setOf("app:wa"), now, others = setOf("hub:home")) { _, share, _ -> SuggestGate.worth(share, 2.0) })
        val shares = ArrayList<Double>()
        Suggester.top(h, setOf("app:wa"), now, others = setOf("hub:home")) { _, share, _ -> shares += share; true }
        assertEquals(0.5, shares.single(), 0.05)
    }
}
