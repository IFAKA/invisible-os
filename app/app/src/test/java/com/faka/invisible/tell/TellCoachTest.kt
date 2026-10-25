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
        fun tap(hold: Long) { g.down(w.now); w.advanceBy(hold); g.up(w.now) }
        fun speaks() = out.events.map { it.second }.filter { it.startsWith("speak ") }.map { it.substring(6) }
        fun waitFor(maxMs: Long = 60000, pred: () -> Boolean) {
            var t = 0L
            while (!pred() && t < maxMs) { w.advanceBy(50); t += 50 }
            assertTrue("condition not reached", pred())
        }
        fun waitSpeak(text: String) = waitFor { speaks().contains(text) }
    }

    private fun rig(taught: Boolean, force: Boolean = false, opp: (Int, TellSolver, Long) -> Opponent = guardOpp): R {
        val w = FakeWorld(); val out = FakeOut(w); out.taughtFlag = taught
        val g = TellGame(solver, out, w, { w.now }, { 0L }, 7, opp, forceCoach = force)
        g.start()
        return R(w, out, g)
    }

    /** The right answer of every step, as a hold in ms: tap, guard, charge, guard, jab, charge, then three taps. */
    private val answers = listOf(100L, 500L, 900L, 500L, 100L, 900L, 100L, 100L, 100L)

    private fun rush(r: R) { for (a in answers) { r.tap(a); r.w.advanceBy(50) } }

    // ---- pacing ----
    @Test fun pacingFormula() {
        assertEquals(600L + 250L * 5, TellVoice.pauseAfter("one two three four five"))   // 1.85 s
        assertEquals(850L, TellVoice.pauseAfter("Jab."))
        assertEquals(400L + 550L * 3, TellVoice.speakMs("Duel. One button."))
        assertEquals(1500L + 400L, TellVoice.quietAfterSound(400))
        assertEquals(10000L, TellVoice.HINT_MS)
    }

    @Test fun everyPracticeSentenceIsAtMostSevenWords() {
        assertTrue(TellVoice.ALL.size > 25)
        for (t in TellVoice.ALL) assertTrue(t, TellVoice.words(t) in 1..7)
    }

    @Test fun sentencesAreSpokenWithTheHumanPauseBetween() {
        val r = rig(false)
        r.waitSpeak("Tap the button.")
        val sp = r.out.events.filter { it.second.startsWith("speak ") }
        assertEquals(listOf("Duel. One button.", "To leave, hold three seconds.", "Tap the button."), sp.map { it.second.substring(6) })
        for (k in 0 until sp.size - 1) {
            val line = sp[k].second.substring(6)
            val need = TellVoice.speakMs(line) + TellVoice.pauseAfter(line)
            assertTrue("gap after '$line' was ${sp[k + 1].first - sp[k].first}", sp[k + 1].first - sp[k].first >= need)
        }
    }

    @Test fun nothingAutoAdvancesPastAStepWithoutAPress() {
        val r = rig(false)
        r.waitSpeak("Tap the button.")
        r.w.advanceBy(TellVoice.HINT_MS * 3)
        assertFalse(r.speaks().contains("Jab."))
        assertFalse(r.speaks().contains("Now press and hold."))
    }

    // ---- drills ----
    @Test fun drillsProgressOnRightHoldsAndConfirmInOneWord() {
        val r = rig(false)
        r.waitSpeak("Tap the button."); r.tap(100)
        r.waitSpeak("Jab.")
        assertEquals(1, r.out.count("play JAB ME"))
        r.waitSpeak("Let go after the click."); assertEquals(1, r.out.count("play DETENT_1"))   // the click is heard once first
        r.tap(500); r.waitSpeak("Guard.")
        r.waitSpeak("Let go after the second."); r.tap(900); r.waitSpeak("Charge.")
        r.waitSpeak("The robot will jab.")
    }

    @Test fun wrongHoldGivesFeedbackAndThreeWrongSkips() {
        val r = rig(false)
        r.waitSpeak("Tap the button.")
        r.tap(500); r.waitSpeak("Tap quickly.")
        assertTrue(r.speaks().contains("That was a guard."))
        r.tap(900); r.waitFor { r.speaks().count { it == "Tap quickly." } == 2 }
        r.tap(500); r.waitSpeak(TellVoice.SKIP)
        r.waitSpeak("Now press and hold.")
        assertFalse(r.speaks().contains("Jab."))
    }

    @Test fun pressesDuringSpeechAreJudgedAtOnceAndStopTheVoice() {
        val r = rig(false)
        r.w.advanceBy(300)             // the very first sentence is being said
        val stops = r.out.count("stopSpeech")
        r.tap(100)
        assertTrue(r.out.count("stopSpeech") > stops)
        r.w.advanceBy(100)
        assertTrue(r.speaks().contains("Jab."))
        r.tap(100)                     // "Jab." is being said: a press goes to step 2 at once and is judged there (wrong: a jab)
        r.w.advanceBy(100)
        assertTrue(r.speaks().contains("That was a jab."))
    }

    // ---- the three mini-rounds ----
    @Test fun miniRoundsRightAndWrong() {
        val r = rig(false)
        for (a in answers.take(3)) { r.tap(a); r.w.advanceBy(50) }
        r.waitSpeak("You guard.")
        r.tap(900); r.waitSpeak("Hold for the first click.")   // a charge instead of a guard
        assertTrue(r.speaks().contains("That was a charge."))
        assertEquals(0, r.out.count("play PARRY_CLANG"))
        r.tap(500)
        r.waitSpeak(TellVoice.RULE_A)
        assertEquals(1, r.out.count("play PARRY_CLANG ME"))
        r.waitSpeak("You jab."); r.tap(100); r.waitSpeak(TellVoice.RULE_B)
        r.waitSpeak("You charge."); r.tap(900); r.waitSpeak(TellVoice.RULE_C)
        assertEquals(1, r.out.count("play CRACK_BOOM"))
    }

    // ---- hints ----
    @Test fun silenceGivesHintsEveryTenSecondsThenTheLeaveLineThenDemo() {
        val r = rig(false)
        r.waitSpeak("Tap the button.")
        val t0 = r.out.times("speak Tap the button.").first()
        r.waitSpeak("Press the button.")
        val h1 = r.out.times("speak Press the button.").first()
        assertTrue("first hint after ${h1 - t0}", h1 - t0 >= TellVoice.speakMs("Tap the button.") + TellVoice.HINT_MS)
        r.waitFor { r.speaks().count { it == "Press the button." } == 2 }
        val h2 = r.out.times("speak Press the button.")[1]
        assertTrue(h2 - h1 >= TellVoice.HINT_MS)
        r.waitSpeak(TellVoice.LEAVE_HINT)
        assertEquals(1, r.speaks().count { it == TellVoice.LEAVE_HINT })
        r.waitSpeak("Now press and hold.")   // after the third hint a demo plays and the lesson goes on
        assertTrue(r.out.count("play JAB ME") >= 1)
        assertFalse(r.speaks().contains("Jab."))
    }

    @Test fun lectureStepsHintToContinueAndNeverAdvanceAlone() {
        val r = rig(false)
        for (a in answers.take(6)) { r.tap(a); r.w.advanceBy(50) }
        r.waitSpeak("You get one back each round.")
        assertEquals(6, r.out.count("status "))
        r.waitSpeak("Press to continue.")
        r.w.advanceBy(TellVoice.HINT_MS * 5)
        assertFalse(r.speaks().contains("You each have four life."))
        r.tap(100)
        r.waitSpeak("The robot is on your left.")
    }

    @Test fun leaveHintIsSaidOnlyInIntroAndAfterTheSecondHint() {
        val r = rig(false)
        r.waitSpeak("Tap the button.")
        r.waitSpeak(TellVoice.LEAVE_HINT)
        assertEquals(2, r.speaks().count { it.contains("hold three seconds", ignoreCase = true) || it == TellVoice.LEAVE_HINT } )
    }

    @Test fun holdingThreeSecondsLeavesEvenWhileTheVoiceSpeaks() {
        val r = rig(false)
        r.w.advanceBy(500)
        r.g.down(r.w.now); r.w.advanceBy(3100)
        assertTrue(r.out.left)
        assertFalse(r.out.taughtFlag)
    }

    // ---- the coached match ----
    private fun toMatch(r: R) { rush(r); r.w.advanceBy(TellGame.INTRO_MS + 10) }

    @Test fun practiceThenFirstMatchThenFlag() {
        val r = rig(false)
        rush(r)
        assertEquals(TellVoice.firstMatch(1), r.speaks().last())
        assertEquals(0, r.out.count("startBed"))
        r.w.advanceBy(TellGame.INTRO_MS + 10)
        assertEquals(1, r.out.count("startBed"))
        assertFalse(r.out.taughtFlag)
        r.w.advanceBy(5000L * 20 + 2000)
        assertTrue(r.out.taughtFlag)
        assertEquals(1, r.out.count("saveTaught"))
    }

    @Test fun leavingMidPracticeDoesNotSetTheFlag() {
        val r = rig(false)
        r.tap(100); r.w.advanceBy(500)
        r.g.down(r.w.now); r.w.advanceBy(3100)
        assertTrue(r.out.left); assertFalse(r.out.taughtFlag)
    }

    @Test fun coachedMatchNarratesShortAndRoundsLastFiveSeconds() {
        val r = rig(false); toMatch(r)
        r.w.advanceBy(5000L * 3)
        assertTrue(r.speaks().count { it == "Both guard. Nothing." } >= 2)
        val t = r.out.times("tension")
        assertEquals(5000L, t[2] - t[1])
    }

    @Test fun coachedMatchHasNoTensionLayers() {
        val jabber: (Int, TellSolver, Long) -> Opponent = { _, _, _ ->
            object : Opponent {
                override fun choose(round: Int, state: State, rng: Random) = Move.JAB
                override fun observe(playerMove: Move, round: Int) {}
            }
        }
        val r = rig(false, opp = jabber); toMatch(r)
        r.w.advanceBy(5000L * 6)
        assertTrue(r.out.count("play PARRY_CLANG") >= 3)
        val t = r.out.events.map { it.second }.filter { it.startsWith("tension ") }
        assertTrue(t.isNotEmpty() && t.all { it == "tension 0" })
        assertTrue(r.speaks().any { it.contains("Their life") })
    }

    @Test fun narrationIsShortAndHasLifeAndEnergyRemarksOnlyWhenNeeded() {
        val s = State(3, 3, 4, 4)
        assertEquals("They jab. You guard. Parry. Their life three.", TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.JAB)))
        assertEquals("Both guard. Nothing.", TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD)))
        assertTrue(TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD), chargeReady = true).endsWith("Charge ready."))
        assertTrue(TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD), lowEnergy = true).endsWith("Low energy."))
        assertFalse(TellVoice.narration(TellRules.resolve(s, Move.GUARD, Move.GUARD)).contains("nergy"))
    }

    @Test fun resultSentencesCoverAllNinePairsInFiveWordsOrFewer() {
        for (m in Move.values()) for (t in Move.values()) {
            val text = TellVoice.result(TellRules.resolve(State(5, 5, 4, 4), m, t))
            assertTrue("$m $t -> $text", TellVoice.words(text) in 1..5)
        }
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
        r.waitSpeak("Tap the button.")
        assertEquals("Duel. One button.", r.speaks().first())
    }
}
