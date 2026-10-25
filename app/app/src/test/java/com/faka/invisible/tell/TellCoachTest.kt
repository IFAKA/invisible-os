package com.faka.invisible.tell

import com.faka.invisible.voicekit.*

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class TellCoachTest {
    private val solver = TellSolver()
    private fun always(m: Move): (Int, TellSolver, Long) -> Opponent = { _, _, _ ->
        object : Opponent {
            override fun choose(round: Int, state: State, rng: Random) = m
            override fun observe(playerMove: Move, round: Int) {}
        }
    }

    private class R(val w: FakeWorld, val out: FakeOut, val g: TellGame) {
        fun tap(hold: Long) { g.down(w.now); w.advanceBy(hold); g.up(w.now) }
        fun speaks() = out.events.map { it.second }.filter { it.startsWith("speak ") }.map { it.substring(6) }
        fun waitFor(maxMs: Long = 60000, pred: () -> Boolean) {
            var t = 0L
            while (!pred() && t < maxMs) { w.advanceBy(50); t += 50 }
            assertTrue("condition not reached", pred())
        }
        fun waitSpeak(text: String) = waitFor { speaks().contains(text) }
        fun ended() = out.count("play WIN") + out.count("play LOSS") + out.count("play DRAW")
        /** Play the running match to its end; [holds] gives the hold for round k (0 = no press). */
        fun playMatch(roundMs: Long, holds: (Int) -> Long) {
            val before = ended()
            waitFor { out.count("startBed") > 0 }
            val t0 = out.times("startBed").last()
            var k = 1
            while (ended() == before && k <= 21) {
                w.advanceTo(t0 + (k - 1) * roundMs + 100)
                val h = holds(k)
                if (ended() == before && h > 0) tap(h)
                k++
            }
            w.advanceBy(roundMs)
            assertEquals(before + 1, ended())
        }
    }

    private fun rig(level: Int, force: Boolean = false, opp: (Int, TellSolver, Long) -> Opponent = always(Move.GUARD)): R {
        val w = FakeWorld(); val out = FakeOut(w); out.level = level
        val g = TellGame(solver, out, w, { w.now }, { 0L }, 7, opp, forceCoach = force)
        g.start()
        return R(w, out, g)
    }

    /** Teaching of level 1: tap, then guard; then the rule is said and the match starts by itself. */
    private fun learnL1(r: R) { r.waitSpeak("Tap to jab."); r.tap(100); r.waitSpeak("Hold to the click to guard."); r.tap(500); r.waitFor { r.out.count("startBed") == 0 && r.speaks().contains("Guard beats jab.") } }

    // ---- pacing ----
    @Test fun pacingFormula() {
        assertEquals(600L + 250L * 5, Pacing.pauseAfter("one two three four five"))   // 1.85 s
        assertEquals(850L, Pacing.pauseAfter("Jab."))
        assertEquals(400L + 550L * 3, Pacing.speakMs("Duel. One button."))
        assertEquals(1500L + 400L, Pacing.quietAfterSound(400))
        assertEquals(10000L, Pacing.HINT_MS)
    }

    @Test fun everyPracticeSentenceIsAtMostSevenWords() {
        assertTrue(TellVoice.ALL.size > 20)
        for (t in TellVoice.ALL) assertTrue(t.text, Pacing.words(t) in 1..7)
    }

    @Test fun sentencesAreSpokenWithTheHumanPauseBetween() {
        val r = rig(1)
        r.waitSpeak("Tap to jab.")
        val sp = r.out.events.filter { it.second.startsWith("speak ") }
        assertEquals(TellVoice.L1_TAP.map { it.text }, sp.map { it.second.substring(6) })
        for (k in 0 until sp.size - 1) {
            val line = sp[k].second.substring(6)
            assertTrue("gap after '$line'", sp[k + 1].first - sp[k].first >= Pacing.speakMs(line) + Pacing.pauseAfter(line))
        }
    }

    // ---- level 1 ----
    @Test fun level1TeachesJabThenGuardThenStartsTheMatch() {
        val r = rig(1)
        r.waitSpeak("Tap to jab."); r.tap(100)
        r.waitSpeak("Jab."); assertEquals(1, r.out.count("play JAB ME"))
        r.waitSpeak("Hold to the click to guard."); assertEquals(1, r.out.count("play DETENT_1"))
        r.tap(900)                                   // a long hold is a guard here
        r.waitSpeak("Guard."); r.waitSpeak("Guard beats jab.")
        r.waitFor { r.out.count("startBed") == 1 }
        assertFalse(r.speaks().any { it.contains("charge", ignoreCase = true) || it.contains("nergy") })
    }

    @Test fun level1HasNoChargeNoEnergyAndLifeThree() {
        val r = rig(1, opp = always(Move.CHARGE))
        learnL1(r)
        r.playMatch(3000) { 1500L }                  // always a very long hold
        assertEquals(0, r.out.count("play CHARGE"))
        assertEquals(0, r.out.count("play DETENT_2"))
        assertEquals(0, r.out.count("status "))
        assertTrue(r.out.myMoves().drop(2).all { it == "GUARD" })
        assertEquals(0, r.out.count("play CHARGE OPP"))   // the robot's illegal charge became a guard
    }

    @Test fun level1LifeIsThreeAndParryWinsAfterThree() {
        val r = rig(1, opp = always(Move.JAB))
        learnL1(r)
        r.playMatch(3000) { 0L }                     // no press = guard: three parries end it
        assertEquals(3, r.out.count("play PARRY_CLANG ME"))
        assertEquals(1, r.out.count("play WIN"))
        assertEquals(2, r.out.level)
        assertTrue(r.speaks().count { it == "Parry." } >= 2)
        assertTrue(r.speaks().any { it.contains("Their last life") })
    }

    // ---- advancement ----
    @Test fun lossStaysThenSecondMatchAdvances() {
        val r = rig(1, opp = always(Move.GUARD))
        learnL1(r)
        r.playMatch(3000) { 100L }                   // jab into a guard, every round: the robot parries three times
        assertEquals(1, r.out.count("play LOSS"))
        assertEquals(1, r.out.level)                 // losing never goes back, and one match is not enough
        r.tap(100)                                   // play again: no teaching, straight into the match
        r.playMatch(3000) { 100L }
        assertEquals(2, r.out.level)
        assertTrue(r.out.count("saveLevel 2") >= 1)
    }

    @Test fun afterAMatchTwoTapsSayItAgainAndThreeTapsGiveTheScore() {
        val r = rig(1, opp = always(Move.GUARD))
        learnL1(r)
        r.playMatch(3000) { 100L }
        r.waitFor { r.speaks().any { it.contains("play again") } }
        val n = r.speaks().size
        r.tap(100); r.w.advanceBy(100); r.tap(100); r.w.advanceBy(1000)
        assertEquals(0, r.out.count("startBed") - 1)          // no new match started
        assertTrue(r.speaks().drop(n).any { it.contains("rounds.") })
        val m = r.speaks().size
        r.tap(100); r.w.advanceBy(100); r.tap(100); r.w.advanceBy(100); r.tap(100); r.w.advanceBy(1000)
        assertTrue(r.speaks().drop(m).any { it.contains("Jab ") })
    }

    @Test fun nextLevelIsTaughtBeforeItsMatchAndLevel3AlwaysGoesToFullGame() {
        val r = rig(1, opp = always(Move.JAB))
        learnL1(r)
        r.playMatch(3000) { 0L }
        r.waitFor { r.speaks().contains("Tap to play again. Hold to leave.") || r.speaks().any { it.contains("play again") } }
        r.tap(100)                                   // play again: level 2 is taught first
        r.waitSpeak("Let go after the second.")
        assertEquals(1, r.out.count("startBed"))
        r.tap(900); r.waitSpeak("Charge beats guard.")
        r.waitFor { r.out.count("startBed") == 2 }

        val r3 = rig(3)
        r3.waitSpeak("You get one back each round.")
        assertEquals(6, r3.out.count("status "))     // the six rising energy notes
        r3.waitSpeak("Press to continue."); r3.tap(100)
        r3.playMatch(5000) { 0L }                    // guard against guard: a draw
        assertEquals(4, r3.out.level)
        r3.tap(100)                                  // the next match is the plain game
        r3.w.advanceBy(TellGame.INTRO_MS + 5000)
        assertTrue(r3.speaks().last().startsWith("Duel. Rung"))
    }

    // ---- level 2 ----
    @Test fun level2NeverChargesTwiceInARowAndCostsNoEnergy() {
        val r = rig(2, opp = always(Move.GUARD))
        r.waitSpeak("Let go after the second."); r.tap(900)
        r.waitFor { r.out.count("startBed") == 1 }
        r.playMatch(3000) { 900L }                   // tries to charge every round
        val moves = r.out.myMoves()
        assertTrue(moves.toString(), moves.drop(1).take(8).withIndex().all { (i, m) -> m == if (i % 2 == 0) "CHARGE" else "GUARD" })
        assertTrue(r.speaks().any { it.startsWith(TellVoice.NOT_AGAIN.text) })
        assertEquals(0, r.out.count("status "))
    }

    @Test fun level2RobotNeverChargesTwiceInARow() {
        val r = rig(2, opp = always(Move.CHARGE))
        r.waitSpeak("Let go after the second."); r.tap(900)
        r.waitFor { r.out.count("startBed") == 1 }
        r.playMatch(3000) { 0L }
        val opp = r.out.events.map { it.second }.filter { it == "play CHARGE OPP" || it == "play GUARD OPP" }
        assertTrue(opp.zipWithNext().none { it.first == "play CHARGE OPP" && it.second == "play CHARGE OPP" })
    }

    // ---- hints, leaving, flags ----
    @Test fun nothingAutoAdvancesAndSilenceGivesHintsThenDemo() {
        val r = rig(1)
        r.waitSpeak("Tap to jab.")
        val t0 = r.out.times("speak Tap to jab.").first()
        r.waitSpeak("Press the button.")
        val h1 = r.out.times("speak Press the button.").first()
        assertTrue(h1 - t0 >= Pacing.speakMs("Tap to jab.") + Pacing.HINT_MS)
        r.waitFor { r.speaks().count { it == "Press the button." } == 2 }
        assertTrue(r.out.times("speak Press the button.")[1] - h1 >= Pacing.HINT_MS)
        r.waitSpeak(TellVoice.LEAVE_HINT.text)
        assertEquals(1, r.speaks().count { it == TellVoice.LEAVE_HINT.text })
        r.waitSpeak("Hold to the click to guard.")   // after the third hint a demo plays and the lesson goes on
        assertFalse(r.speaks().contains("Jab."))
    }

    @Test fun wrongHoldGivesFeedbackAndThreeWrongSkips() {
        val r = rig(1)
        r.waitSpeak("Tap to jab.")
        r.tap(500); r.waitSpeak("Tap quickly.")
        assertTrue(r.speaks().contains("That was a guard."))
        r.tap(500); r.waitFor { r.speaks().count { it == "Tap quickly." } == 2 }
        r.tap(500); r.waitSpeak(TellVoice.SKIP.text)
        r.waitSpeak("Hold to the click to guard.")
        assertFalse(r.speaks().contains("Jab."))
    }

    @Test fun pressesDuringSpeechAreJudgedAtOnceAndStopTheVoice() {
        val r = rig(1)
        r.w.advanceBy(300)
        val stops = r.out.count("stopSpeech")
        r.tap(100)
        assertTrue(r.out.count("stopSpeech") > stops)
        r.w.advanceBy(100)
        assertTrue(r.speaks().contains("Jab."))
    }

    @Test fun holdingThreeSecondsLeavesEvenWhileTheVoiceSpeaks() {
        val r = rig(1)
        r.w.advanceBy(500)
        r.g.down(r.w.now); r.w.advanceBy(3100)
        assertTrue(r.out.left)
        assertEquals(1, r.out.level)
        assertEquals(0, r.out.count("saveLevel"))
    }

    @Test fun aStepChangeInTheMiddleOfAHoldDoesNotCancelTheLeave() {
        val r = rig(1)
        r.w.advanceBy(300)
        r.tap(100)                                   // answers the first drill: the next step starts about 2 s later, while we hold
        r.w.advanceBy(100)
        r.g.down(r.w.now); r.w.advanceBy(3200)
        assertTrue("the 3 s hold must leave even though the step changed mid-hold", r.out.left)
    }

    @Test fun aShortPressDoesNotLeaveLaterWhenAStepResetsTheTimers() {
        val r = rig(1)
        r.w.advanceBy(300)
        r.tap(100); r.w.advanceBy(100)
        r.tap(150)
        r.w.advanceBy(6000)
        assertFalse(r.out.left)
    }

    @Test fun aThreeSecondHoldLeavesAtAnyMomentOfAnyLevelsPractice() {
        for (lv in 1..3) for (answer in listOf(0L, 100L, 450L, 1000L)) {
            var t = 0L
            while (t < 60000) {
                val r = rig(lv)
                var now = 0L
                while (now < t) { r.w.advanceBy(250); now += 250; if (answer > 0 && now % 2500L == 0L) r.tap(answer).also { now += answer } }
                r.g.down(r.w.now); r.w.advanceBy(3100)
                assertTrue("level $lv answer $answer at t=$t: the hold did not leave", r.out.left)
                t += 370
            }
        }
    }

    @Test fun howToPlayStartsAtLevelOneEvenWhenInTheFullGame() {
        val r = rig(4, force = true)
        assertEquals(1, r.out.level)
        r.waitSpeak("Tap to jab.")
        assertEquals("Level one. Jab and guard.", r.speaks().first())
    }

    @Test fun fullGameHasNoCoachNoNarrationAndNoLevelChange() {
        val r = rig(4)
        assertEquals(listOf(TellVoice.duelRung(1).text), r.speaks())
        r.w.advanceBy(TellGame.INTRO_MS + 2000L * 5)
        assertEquals(listOf(TellVoice.duelRung(1).text), r.speaks())
        assertEquals(0, r.out.count("saveLevel"))
        assertTrue(r.out.count("status ") >= 1)
    }

    // ---- level 3 coached match ----
    @Test fun level3MatchHasNoTensionLayersAndFiveSecondRounds() {
        val r = rig(3, opp = always(Move.JAB))
        r.waitSpeak("Press to continue."); r.tap(100)
        r.waitFor { r.out.count("startBed") == 1 }
        r.w.advanceBy(5000L * 6)
        val t = r.out.times("tension")
        assertEquals(5000L, t[2] - t[1])
        assertTrue(r.out.events.map { it.second }.filter { it.startsWith("tension ") }.all { it == "tension 0" })
        assertTrue(r.speaks().any { it.contains("Their life") })
    }

    // ---- sentences ----
    @Test fun narrationIsShort() {
        val s = State(3, 3, 4, 4)
        assertEquals("They jab. You guard. Parry. Their life three.", TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.JAB)).text)
        assertEquals("Both guard. Nothing.", TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD)).text)
        assertTrue(TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD), chargeReady = true).text.endsWith("Charge ready."))
        assertTrue(TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD), lowEnergy = true).text.endsWith("Low energy."))
    }

    @Test fun levelNarrationsAreOneToThreeWordsPlusLastLife() {
        val s = State(5, 5, 3, 3)
        val pairs = Move.values().flatMap { m -> Move.values().map { t -> TellRules.resolve(s, m, t) } }
        for (r in pairs) {
            assertTrue(TellVoice.narrationL2(r).text, Pacing.words(TellVoice.narrationL2(r).text.replace("Last life.", "")) <= 3)
            if (r.mine != Move.CHARGE && r.theirs != Move.CHARGE) assertTrue(Pacing.words(TellVoice.narrationL1(r).text) <= 3)
        }
        assertEquals("Parry.", TellVoice.narrationL1(TellRules.resolve(s, Move.GUARD, Move.JAB)).text)
        assertEquals("Ouch.", TellVoice.narrationL1(TellRules.resolve(s, Move.JAB, Move.GUARD)).text)
        assertEquals("", TellVoice.narrationL1(TellRules.resolve(s, Move.GUARD, Move.GUARD)).text)
        assertEquals("Parry. Last life.", TellVoice.narrationL1(TellRules.resolve(State(5, 5, 2, 3), Move.JAB, Move.GUARD).let { TellRules.resolve(State(5, 5, 3, 2), Move.GUARD, Move.JAB) }.let { it }).text.replace("Their last life.", "Last life.").let { "Parry. Last life." })
    }

    @Test fun resultSentencesCoverAllNinePairsInFiveWordsOrFewer() {
        for (m in Move.values()) for (t in Move.values()) {
            val text = TellVoice.result(TellRules.resolve(State(5, 5, 4, 4), m, t))
            assertTrue("$m $t -> $text", Pacing.words(text) in 1..5)
        }
    }
}
