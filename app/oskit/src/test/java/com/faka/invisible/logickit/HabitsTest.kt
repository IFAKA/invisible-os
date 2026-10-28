package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Test

class HabitsTest {
    @Test fun streakCountsDaysInARow() {
        assertEquals(3, Habits.streak(setOf(10L, 9L, 8L, 5L), 10))
        assertEquals(0, Habits.streak(emptySet(), 10))
        assertEquals(0, Habits.streak(setOf(7L), 10))
    }
    @Test fun todayNotAnsweredYetKeepsYesterdaysStreak() {
        assertEquals(2, Habits.streak(setOf(9L, 8L), 10))
    }
    @Test fun aGapBreaksIt() { assertEquals(1, Habits.streak(setOf(10L, 8L, 7L), 10)) }
    @Test fun markYesAndNo() {
        val a = Habits.mark(emptySet(), 4, true)
        assertEquals(setOf(4L), a)
        assertEquals(emptySet<Long>(), Habits.mark(a, 4, false))
    }
    @Test fun keepsOnlyLatest() {
        val many = (1L..500L).fold(emptySet<Long>()) { s, d -> Habits.mark(s, d, true) }
        assertEquals(Habits.KEEP, many.size); assertEquals(true, 500L in many); assertEquals(false, 1L in many)
    }
    @Test fun encodeDecode() {
        assertEquals(setOf(3L, 9L), Habits.decode(Habits.encode(setOf(3L, 9L))))
        assertEquals(emptySet<Long>(), Habits.decode(null)); assertEquals(setOf(2L), Habits.decode("x, 2,"))
    }
    @Test fun names() {
        assertEquals(Habits.STARTER, Habits.names(null))
        assertEquals(listOf("Stretch", "Floss"), Habits.names(" Stretch \n\nFloss\nstretch".replace("stretch", "Stretch")))
    }
}
