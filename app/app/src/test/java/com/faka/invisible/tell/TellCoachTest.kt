package com.faka.invisible.tell

import com.faka.invisible.TellVoice
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class TellCoachTest {
    private val solver = TellSolver()
    private val guardOpp: (Int, TellSolver, Long) -> Opponent = { _, _, _ ->
        object : Opponent {
            override fun choose(round: Int, state: State, rng: Random) = Move.GUARD
            override fun observe(playerMove: Move, round: Int) {}
        }
    }

    private class R(val w: FakeWorld, val out: FakeOut, val g: TellGame) {
        fun tap(hold: Long) { g.down(w.now); w.advanceBy(hold); g.up(w.now); }
        fun speaks() = out.events.map { it.second }.filter { it.startsWith("speak ") }.map { it.substring(6) }
    }

    private fun rig(taught: Boolean, force: Boolean = false): R {
        val w = FakeWorld(); val out = FakeOut(w); out.taughtFlag = taught
        val g = TellGame(solver, out, w, { w.now }, { 0L }, 7, guardOpp, forceCoach = force)
        g.start()
        return R(w, out, g)
    }

    private fun toFirstDrill(r: R) = r.w.advanceBy(TellVoice.speakMs(TellVoice.INTRO) + TellGame.DRILL_GAP_MS + 10)

    @Test fun drillsProgressOnRightHolds() {
        val r = rig(false); toFirstDrill(r)
        assertEquals(listOf(TellVoice.INTRO, TellVoice.DRILLS[0]), r.speaks())
        r.tap(100); r.w.advanceBy(3000)
        assertTrue(r.speaks().contains("Jab."))
        assertEquals(TellVoice.DRILLS[1], r.speaks().last())
        r.tap(500); r.w.advanceBy(3000)
        assertTrue(r.speaks().contains("Guard."))
        assertEquals(TellVoice.DRILLS[2], r.speaks().last())
        r.tap(900); r.w.advanceBy(TellVoice.speakMs(TellVoice.RULE) + 3000)
        assertTrue(r.speaks().contains("Charge."))
        assertTrue(r.speaks().contains(TellVoice.RULE))
        assertEquals(TellVoice.firstMatch(1), r.speaks().last())
        assertEquals(0, r.out.count("startBed"))   // the bed starts after the intro
        r.w.advanceBy(TellGame.INTRO_MS + 10)
        assertEquals(1, r.out.count("startBed"))
    }

    @Test fun wrongHoldRepeatsAndThreeWrongSkips() {
        val r = rig(false); toFirstDrill(r)
        r.tap(500); r.w.advanceBy(7000)
        assertTrue(r.speaks().contains(TellVoice.wrong(1, 0)))
        assertEquals(TellVoice.DRILLS[0], r.speaks().last())   // repeated
        r.tap(900); r.w.advanceBy(7000)
        assertEquals(TellVoice.DRILLS[0], r.speaks().last())
        r.tap(500); r.w.advanceBy(7000)                          // third wrong: moves on
        assertEquals(TellVoice.DRILLS[1], r.speaks().last())
        assertFalse(r.speaks().contains("Jab."))
    }

    @Test fun noSpokenSecondsInDrills() {
        val all = TellVoice.DRILLS + TellVoice.wrong(0, 0) + TellVoice.wrong(1, 1) + TellVoice.wrong(2, 2)
        assertTrue(all.none { it.contains("half a second") || it.contains("one second") })
    }

    private fun playCoached(r: R) {
        toFirstDrill(r)
        r.tap(100); r.w.advanceBy(3000); r.tap(500); r.w.advanceBy(3000); r.tap(900)
        r.w.advanceBy(TellVoice.speakMs(TellVoice.RULE) + 3000 + TellGame.INTRO_MS)
    }

    @Test fun flagSetOnlyAfterAFullCoachedMatch() {
        val r = rig(false)
        playCoached(r)
        assertFalse(r.out.taughtFlag)
        r.w.advanceBy(4000L * 20 + 2000)   // the whole match, guard against guard: draw after 20 rounds
        assertTrue(r.out.taughtFlag)
        assertEquals(1, r.out.count("saveTaught"))
    }

    @Test fun leavingMidPracticeDoesNotSetTheFlag() {
        val r = rig(false); toFirstDrill(r)
        r.g.down(r.w.now); r.w.advanceBy(3100)
        assertTrue(r.out.left)
        assertFalse(r.out.taughtFlag)
    }

    @Test fun coachedMatchNarratesEveryRoundAndLastsFourSeconds() {
        val r = rig(false); playCoached(r)
        r.w.advanceBy(4000L * 3)
        assertTrue(r.speaks().count { it == "Nothing happens." } >= 2)
        val beds = r.out.times("tension")
        assertEquals(4000L, beds[2] - beds[1])
    }

    @Test fun taughtPlayersGetNoCoachAndNoNarration() {
        val r = rig(true)
        assertEquals(listOf(TellVoice.duelRung(1)), r.speaks())
        r.w.advanceBy(TellGame.INTRO_MS + 2000L * 5)
        assertEquals(listOf(TellVoice.duelRung(1)), r.speaks())
        assertEquals(0, r.out.count("saveTaught"))
    }

    @Test fun howToPlayCoachesEvenWhenTaught() {
        val r = rig(true, force = true)
        assertEquals(listOf(TellVoice.INTRO), r.speaks())
    }

    @Test fun resultSentencesCoverAllNinePairs() {
        for (m in Move.values()) for (t in Move.values()) {
            val s = State(5, 5, 4, 4)
            val text = TellVoice.result(TellRules.resolve(s, m, t))
            assertTrue("$m $t -> $text", text.isNotBlank())
            assertTrue("$m $t too long: $text", text.split(" ").size <= 6)
        }
    }
}
