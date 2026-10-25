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

    private fun rig(taught: Boolean, force: Boolean = false, opp: (Int, TellSolver, Long) -> Opponent = guardOpp): R {
        val w = FakeWorld(); val out = FakeOut(w); out.taughtFlag = taught
        val g = TellGame(solver, out, w, { w.now }, { 0L }, 7, opp, forceCoach = force)
        g.start()
        return R(w, out, g)
    }

    private fun lessonsMs() = TellVoice.LESSONS.sumOf { TellVoice.speakMs(it) + TellGame.DRILL_GAP_MS } + 3000L

    private fun toFirstDrill(r: R) = r.w.advanceBy(TellVoice.speakMs(TellVoice.INTRO) + TellGame.DRILL_GAP_MS + 10)

    @Test fun drillsProgressOnRightHolds() {
        val r = rig(false); toFirstDrill(r)
        assertEquals(listOf(TellVoice.INTRO, TellVoice.DRILLS[0]), r.speaks())
        r.tap(100); r.w.advanceBy(3000)
        assertTrue(r.speaks().contains("Jab."))
        assertEquals(TellVoice.DRILLS[1], r.speaks().last())
        assertEquals(1, r.out.count("play DETENT_1"))   // the click is heard once before the prompt
        r.tap(500); r.w.advanceBy(3000)
        assertTrue(r.speaks().contains("Guard."))
        assertEquals(TellVoice.DRILLS[2], r.speaks().last())
        r.tap(900); r.w.advanceBy(lessonsMs() + 3000)
        assertTrue(r.speaks().contains("Charge."))
        assertEquals(TellVoice.LESSONS, r.speaks().filter { it in TellVoice.LESSONS })
        assertEquals(6, r.out.count("status "))   // energy notes 0..5 played once
        assertEquals(TellVoice.firstMatch(1), r.speaks().last())
        assertEquals(0, r.out.count("startBed"))
        r.w.advanceBy(TellGame.INTRO_MS + 10)
        assertEquals(1, r.out.count("startBed"))
    }

    @Test fun wrongHoldGivesFeedbackAndThreeWrongSkips() {
        val r = rig(false); toFirstDrill(r)
        r.tap(500); r.w.advanceBy(100)
        assertEquals(TellVoice.wrong(1, 0), r.speaks().last())
        r.tap(900); r.w.advanceBy(100)
        assertEquals(TellVoice.wrong(2, 0), r.speaks().last())
        r.tap(500); r.w.advanceBy(7000)                          // third wrong: moves on
        assertEquals(TellVoice.DRILLS[1], r.speaks().last())
        assertFalse(r.speaks().contains("Jab."))
    }

    @Test fun pressesDuringSpeechAreJudgedAndStopTheVoice() {
        val r = rig(false); toFirstDrill(r)
        r.tap(100)                       // success: "Jab." is being spoken now
        r.w.advanceBy(50)
        val stops = r.out.count("stopSpeech")
        r.tap(100)                       // press during that speech: starts drill 2 at once, judged against it (wrong: a jab)
        assertTrue(r.out.count("stopSpeech") > stops)
        assertEquals(TellVoice.wrong(0, 1), r.speaks().last())
        r.tap(500); r.w.advanceBy(3000)  // now a right guard is accepted even right after wrong feedback began
        assertTrue(r.speaks().contains("Guard."))
    }

    @Test fun pressAfterWrongFeedbackIsNotIgnored() {
        val r = rig(false); toFirstDrill(r)
        r.tap(500); r.w.advanceBy(300)   // feedback speaking
        r.tap(100); r.w.advanceBy(100)
        assertTrue(r.speaks().contains("Jab."))
    }

    @Test fun silenceGivesHintsEverySevenSecondsThenDemoAndAdvance() {
        val r = rig(false); toFirstDrill(r)
        val base = r.speaks().size
        r.w.advanceBy(TellVoice.speakMs(TellVoice.DRILLS[0]) + TellGame.HINT_MS + 50)
        assertEquals(TellVoice.hint(1), r.speaks().last())
        r.w.advanceBy(TellVoice.speakMs(TellVoice.hint(1)) + TellGame.HINT_MS + 50)
        assertEquals(TellVoice.hint(2), r.speaks().last())
        assertTrue(TellVoice.hint(2).contains("leave"))
        r.w.advanceBy(TellVoice.speakMs(TellVoice.hint(2)) + TellGame.HINT_MS + 50)   // third: demo
        assertEquals(TellVoice.DEMO, r.speaks().last())
        r.w.advanceBy(3000)
        assertEquals(TellVoice.DRILLS[1], r.speaks().last())
        assertTrue(r.out.count("play JAB") >= 1)
        assertTrue(base < r.speaks().size)
    }

    @Test fun holdingThreeSecondsLeavesEvenWhileTheVoiceSpeaks() {
        val r = rig(false)
        r.w.advanceBy(500)                // the intro is being spoken
        r.g.down(r.w.now); r.w.advanceBy(3100)
        assertTrue(r.out.left)
        assertFalse(r.out.taughtFlag)
    }

    @Test fun introSaysHowToLeave() {
        assertTrue(TellVoice.INTRO.contains("three seconds"))
    }

    @Test fun noSpokenSecondsInDrills() {
        val all = TellVoice.DRILLS + TellVoice.wrong(0, 0) + TellVoice.wrong(1, 1) + TellVoice.wrong(2, 2)
        assertTrue(all.none { it.contains("half a second") || it.contains("one second") })
    }

    private fun playCoached(r: R) {
        toFirstDrill(r)
        r.tap(100); r.w.advanceBy(3000); r.tap(500); r.w.advanceBy(3000); r.tap(900)
        r.w.advanceBy(lessonsMs() + 3000 + TellGame.INTRO_MS)
    }

    @Test fun flagSetOnlyAfterAFullCoachedMatch() {
        val r = rig(false)
        playCoached(r)
        assertFalse(r.out.taughtFlag)
        r.w.advanceBy(6000L * 20 + 2000)   // the whole match, guard against guard: draw after 20 rounds
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
        assertTrue(r.speaks().count { it.startsWith("Nothing happens. Energy") } >= 2)
        val beds = r.out.times("tension")
        assertEquals(6000L, beds[2] - beds[1])
    }

    @Test fun coachedMatchHasNoTensionLayers() {
        val jabber: (Int, TellSolver, Long) -> Opponent = { _, _, _ ->
            object : Opponent {
                override fun choose(round: Int, state: State, rng: Random) = Move.JAB
                override fun observe(playerMove: Move, round: Int) {}
            }
        }
        val r = rig(false, opp = jabber); playCoached(r)
        r.w.advanceBy(6000L * 6)
        assertTrue(r.out.count("play PARRY_CLANG") >= 3)   // the opponent is being hurt: normally tension would rise
        val t = r.out.events.map { it.second }.filter { it.startsWith("tension ") }
        assertTrue(t.isNotEmpty() && t.all { it == "tension 0" })
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

    @Test fun everyLessonFitsInEightSeconds() {
        for (l in TellVoice.LESSONS + TellVoice.INTRO + TellVoice.DRILLS) assertTrue(l, TellVoice.speakMs(l) <= 8000)
        assertTrue(TellVoice.LESSONS.joinToString(" ").let { it.contains("four life") && it.contains("left ear") && it.contains("costs three") })
    }

    @Test fun narrationAddsEnergyAndLifeOnlyWhenLifeChanges() {
        val s = State(3, 3, 4, 4)
        val parry = TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.JAB))
        assertEquals("You guarded. They jabbed. Parry. Energy five. Your life four. Their life three.", parry)
        val calm = TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD))
        assertEquals("Nothing happens. Energy four.", calm)
        for (m in Move.values()) for (t in Move.values()) assertTrue(TellVoice.narration(TellRules.resolve(s, m, t)).contains("Energy"))
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
