package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SuggesterTest {
    private val day = 86_400_000L
    private val cands = setOf("phone:torch", "info:weather", "games:duel", "games:write")
    private fun at(dayOffset: Int, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = 1_700_000_000_000L; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); add(Calendar.DAY_OF_YEAR, dayOffset)
    }.timeInMillis
    private fun p(d: Int, h: Int, i: String) = Suggester.Pick(at(d, h), i)

    @Test fun needsTwoPicksBeforeItIsSuggested() {
        assertEquals(emptyList<String>(), Suggester.top(listOf(p(0, 8, "phone:torch")), cands, at(1, 8)))
        assertEquals(listOf("phone:torch"), Suggester.top(listOf(p(0, 8, "phone:torch"), p(0, 9, "phone:torch")), cands, at(1, 8)))
    }

    @Test fun ignoresNonCandidatesAndCapsAtThree() {
        val h = cands.flatMap { listOf(p(0, 8, it), p(0, 9, it)) } + listOf(p(0, 8, "hub:status"), p(0, 8, "hub:status"))
        val top = Suggester.top(h, cands, at(1, 8))
        assertEquals(3, top.size); assertTrue("hub:status" !in top)
    }

    @Test fun picksAtTheSameHourOfDayWin() {
        val h = listOf(p(0, 8, "info:weather"), p(0, 8, "info:weather"), p(0, 21, "games:duel"), p(0, 21, "games:duel"))
        assertEquals("info:weather", Suggester.top(h, cands, at(1, 8))[0])
        assertEquals("games:duel", Suggester.top(h, cands, at(1, 22))[0])
    }

    @Test fun recentBeatsOld() {
        val h = listOf(p(-60, 8, "phone:torch"), p(-60, 8, "phone:torch"), p(-60, 8, "phone:torch"), p(-1, 8, "info:weather"), p(-1, 8, "info:weather"))
        assertEquals("info:weather", Suggester.top(h, cands, at(0, 8))[0])
    }

    @Test fun hitRateReplaysHistory() {
        val h = (0 until 10).map { p(it, 8, "info:weather") }
        val (hits, total) = Suggester.hitRate(h, cands)
        assertEquals(10, total); assertEquals(8, hits)   // the first two picks cannot be predicted
    }

    private fun q(d: Int, h: Int, i: String, vararg t: String) = Suggester.Pick(at(d, h), i, t.toSet())

    @Test fun theSameSituationWinsOverPlainCounts() {
        // weather is picked more often, but "next turn" was always picked while navigating
        val h = listOf(q(-3, 8, "info:weather"), q(-3, 9, "info:weather"), q(-2, 8, "info:weather"),
            q(-2, 8, "info:turn", "navigating"), q(-1, 8, "info:turn", "navigating"))
        assertEquals("info:weather", Suggester.top(h, setOf("info:weather", "info:turn"), at(0, 8))[0])
        assertEquals("info:turn", Suggester.top(h, setOf("info:weather", "info:turn"), at(0, 8), tags = setOf("navigating"))[0])
    }

    @Test fun choiceLogKeepsTheSituationOfEachPick() {
        val log = ChoiceLog()
        val line = log.record(5L, "HUB", 1, "phone:torch", "", setOf("wired", "unread"))
        val back = ChoiceLog(listOf(line))
        assertEquals(setOf("unread", "wired"), back.history().single().tags)
        assertEquals(emptySet<String>(), ChoiceLog(listOf("5\tHUB\t1\tphone:torch\t")).history().single().tags)   // an old line without the column
    }
}
