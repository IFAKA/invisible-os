package com.faka.invisible.guard

import com.faka.invisible.voicekit.Pacing
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of GUARD, played by simulated people. Numbers are a model, not a measurement of real people (see GAME-GUARD.md). */
class GuardModelTest {
    enum class Style { HUMAN, SPAMMER, NONE }
    class Sim(val name: String, val style: Style, val jitter: Double = 0.0, val delay: Double = 0.0)

    class Result(val stage: Int, val over: Boolean, val enemies: Int, val score: Int, val hearts: Int, val judge: GuardJudge, val run: GuardRun)

    private fun clicks(p: Sim, e: Enemy, rng: Random): List<Double> = when (p.style) {
        Style.NONE -> emptyList()
        Style.SPAMMER -> (0..6).map { it * e.beatMs + p.delay + rng.nextGaussian() * 10 }
        Style.HUMAN -> e.strikesMs.map { it + p.delay + rng.nextGaussian() * p.jitter }
    }

    private fun play(p: Sim, seed: Long, max: Int = 600, judge: GuardJudge = GuardJudge(), run: GuardRun = GuardRun()): Result {
        val rng = Random(seed)
        var n = 0
        while (!run.over && n < max) {
            val e = run.next(rng)
            val enc = judge.Encounter(e)
            for (c in clicks(p, e, rng).sorted()) enc.click(c)
            run.record(enc.finish()); n++
        }
        return Result(run.stage, run.over, n, run.score, run.hearts, judge, run)
    }

    private fun median(xs: List<Int>) = xs.sorted()[xs.size / 2]

    @Test fun ladderBands() {
        val sims = listOf(Sim("novice", Style.HUMAN, 90.0), Sim("good", Style.HUMAN, 45.0), Sim("expert", Style.HUMAN, 20.0),
            Sim("spammer", Style.SPAMMER), Sim("none", Style.NONE))
        val stages = HashMap<String, Int>(); val enemies = HashMap<String, Int>()
        for (s in sims) {
            val rs = (1L..40L).map { play(s, it) }
            stages[s.name] = median(rs.map { it.stage }); enemies[s.name] = median(rs.map { it.enemies })
            println("REPORT ${s.name}: median stage ${stages[s.name]} enemies ${enemies[s.name]} score ${median(rs.map { it.score })} maxStage ${rs.maxOf { it.stage }} over ${rs.count { it.over }}/40")
            if (s.style != Style.HUMAN) assertTrue(s.name, rs.all { it.stage <= 3 && it.over })   // never pass the decoy stage
        }
        assertTrue("expert beats good", enemies.getValue("expert") >= enemies.getValue("good"))
        assertTrue("good beats novice", enemies.getValue("good") >= enemies.getValue("novice"))
        assertTrue("novice gets past the decoy", stages.getValue("novice") >= 4)
        assertTrue("expert reaches mixed waves", stages.getValue("expert") >= 6)
    }

    @Test fun nonClickerNeverPassesTheFirstStage() {
        for (seed in 1L..20L) { val r = play(Sim("none", Style.NONE), seed); assertTrue(r.over); assertEquals(1, r.stage) }
    }

    @Test fun decoyPunishesTheSpammerButNotTheQuiet() {
        val spam = play(Sim("spam", Style.SPAMMER), 3)
        assertTrue(spam.over); assertEquals(3, spam.stage)
        val e = GuardLadder.enemy(Kind.DECOY, false, 80.0, 0, true)
        val j = GuardJudge()
        val quiet = j.Encounter(e).finish(); assertFalse(quiet.hurt); assertTrue(quiet.decoyOk)
        val enc = j.Encounter(e); assertEquals(Verdict.BAIT, enc.click(900.0)); assertTrue(enc.finish().hurt)
    }

    @Test fun practiceNeverHurts() {
        val run = GuardRun(); val rng = Random(1)
        repeat(GuardLadder.PRACTICE) {
            val e = run.next(rng); assertTrue(e.practice)
            run.record(Outcome(e.kind, true, true, 0, 0, 0, false, false))
        }
        assertEquals(3, run.hearts)
    }

    @Test fun strikeVerdicts() {
        val e = GuardLadder.enemy(Kind.THIEF, false, 80.0, 0, true)   // beat 750 ms, strike 3000
        val j = GuardJudge()
        assertEquals(Verdict.PERFECT, j.Encounter(e).click(3030.0))
        assertEquals(Verdict.GOOD, j.Encounter(e).click(3000.0 + e.goodMs - 5))
        assertEquals(Verdict.EARLY, j.Encounter(e).click(2000.0))
        val en = j.Encounter(e); en.miss(0); assertEquals(Verdict.AFTER, en.click(3000.0 + e.goodMs + 100)); assertTrue(en.finish().late)
        // the early click does not use up the strike
        val two = GuardJudge().Encounter(e); assertEquals(Verdict.EARLY, two.click(1800.0)); assertEquals(Verdict.PERFECT, two.click(3010.0)); assertFalse(two.finish().hurt)
    }

    @Test fun knightNeedsBothStrikes() {
        val e = GuardLadder.enemy(Kind.KNIGHT, false, 80.0, 0, false)
        val one = GuardJudge().Encounter(e); one.click(3000.0); assertTrue(one.finish().hurt)
        val both = GuardJudge().Encounter(e); both.click(3000.0); both.click(3750.0); assertFalse(both.finish().hurt)
    }

    @Test fun ogreFakeOutBaitsAnEarlyClick() {
        val e = GuardLadder.enemy(Kind.OGRE, false, 80.0, 0, true)
        val fake = e.fakeMs!!
        val bait = GuardJudge().Encounter(e); assertEquals(Verdict.BAIT, bait.click(fake)); assertTrue(bait.finish().hurt)
        val ok = GuardJudge().Encounter(e); assertEquals(Verdict.PERFECT, ok.click(e.strikesMs[0] + 10)); assertFalse(ok.finish().hurt)
        assertTrue(fake + 0.25 * e.beatMs < e.strikesMs[0] - e.goodMs)   // the bait zone and the window never overlap
    }

    @Test fun calibrationAbsorbsAConstantDelay() {
        for (delay in listOf(200.0, -150.0)) {
            val judge = GuardJudge()
            val p = Sim("laggy", Style.HUMAN, 20.0, delay)
            val first = play(p, 5, max = 3, judge = judge)
            val later = play(p, 6, max = 30, judge = judge, run = GuardRun(4))
            println("REPORT delay $delay -> bias ${judge.biasMs.toInt()}")
            assertEquals(delay, judge.biasMs, 40.0)
            assertTrue(later.hearts == 3 || later.score > 0)
        }
        // without learning, a 200 ms delay misses the strike windows
        val e = GuardLadder.enemy(Kind.THIEF, false, 80.0, 40, true)
        val enc = GuardJudge().Encounter(e); enc.click(e.strikesMs[0] + 200.0); assertTrue(enc.finish().hurt)
    }

    @Test fun biasIsClampedAndSavedValueIsUsed() {
        val j = GuardJudge(); val p = Sim("huge", Style.HUMAN, 10.0, 500.0)
        play(p, 2, max = 12, judge = j)
        assertTrue(j.biasMs <= 300.0 && j.biasMs >= 0.0)
        assertEquals(120.0, GuardJudge(120.0).biasMs, 0.01)
    }

    @Test fun tapAlongFindsTheDelayAndIgnoresOutliers() {
        val ticks = GuardCalibration.tickMs
        val rng = Random(3)
        val clicks = ticks.map { it + 180 + rng.nextGaussian() * 15 } + listOf(ticks[2] + 900.0)
        assertEquals(180.0, GuardCalibration.offset(ticks, clicks)!!, 30.0)
        assertEquals(null, GuardCalibration.offset(ticks, listOf(100.0, 700.0)))
    }

    @Test fun tempoDropsAfterTwoHeartsLostInAWave() {
        val run = GuardRun(6); val rng = Random(1)
        val b0 = run.baseBpm
        repeat(2) { val e = run.next(rng); run.record(Outcome(e.kind, false, true, 0, 0, 0, false, false)) }
        assertEquals(b0 - 10.0, run.baseBpm, 0.01)
        assertEquals(1, run.hearts)
        // and it rises again over clean waves
        val run2 = GuardRun(6)
        repeat(GuardLadder.WAVE * 5) { val e = run2.next(rng); run2.record(Outcome(e.kind, false, false, 1, 0, 1, false, false)) }
        assertTrue(run2.baseBpm > 80.0 && run2.baseBpm <= GuardLadder.MAX_BPM)
    }

    @Test fun fivePerfectsHealAHeart() {
        val run = GuardRun(6); val rng = Random(1)
        val e0 = run.next(rng); run.record(Outcome(e0.kind, false, true, 0, 0, 0, false, false))
        assertEquals(2, run.hearts)
        repeat(5) { val e = run.next(rng); run.record(Outcome(e.kind, false, false, 1, 0, 1, false, false)) }
        assertEquals(3, run.hearts)
    }

    @Test fun windowsAreWideAtFirstAndShrinkSlowly() {
        val beat = 750.0
        assertTrue(GuardLadder.good(0, beat) >= 170.0)
        assertTrue(GuardLadder.good(3, beat) >= 170.0)
        assertTrue(GuardLadder.good(100, beat) in 100.0..110.0)
        assertTrue(GuardLadder.good(0, 400.0) <= 160.0)
    }

    @Test fun sentencesAreShortAndPlain() {
        for (p in GuardVoice.ALL + listOf(GuardVoice.over(10, 20), GuardVoice.over(30, 20))) {
            for (s in p.text.split(". ", "? ")) assertTrue(p.text, Pacing.words(com.faka.invisible.voicekit.Phrase.content(s)) in 1..7)
        }
    }
}
