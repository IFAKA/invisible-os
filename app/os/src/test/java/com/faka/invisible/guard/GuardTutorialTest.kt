package com.faka.invisible.guard

import com.faka.invisible.voicekit.GuardVoice
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole game on a virtual clock, with simulated people. The first two minutes must never leave a person stuck, lost or hurt. */
class GuardTutorialTest {
    private class Env(val onLog: (Env, String) -> Unit = { _, _ -> }) : GuardOut, Timers {
        var t = 0L
        val due = ArrayList<Triple<String, Long, () -> Unit>>()
        val spoken = ArrayList<String>(); val logs = ArrayList<String>()
        var game: GuardGame? = null
        var left = false; var taught = false; var bias: Double? = null; var bestSaved = -1
        var taughtAt = -1L
        var seq = 0
        override fun nowFrame() = t * GuardSynth.SR / 1000
        override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) {}
        override fun clearScheduled() {}
        override fun speak(text: Phrase) {
            spoken.add(text.text)
            set("speechdone", 250L + 330L * text.text.split(" ").size) { game?.onSpeechDone() }
        }
        override fun stopSpeech() { clear("speechdone") }
        override fun best() = 0
        override fun saveBest(score: Int) { bestSaved = score }
        override fun bias() = bias
        override fun saveBias(ms: Double) { bias = ms }
        override fun taught() = taught
        override fun saveTaught(v: Boolean) { taught = v; if (v && taughtAt < 0) taughtAt = t }
        override fun log(line: String) { logs.add(line); onLog(this, line) }
        override fun leave() { left = true }
        override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); due.add(Triple(key, t + delayMs, task)) }
        override fun clear(key: String) { due.removeAll { it.first == key } }
        fun at(ms: Long, task: () -> Unit) { set("ev${seq++}", ms, task) }
        fun click(afterMs: Long) = at(afterMs) { game?.down(t); game?.up(t + 20) }
        fun advance(ms: Long) {
            val end = t + ms
            while (true) {
                val next = due.filter { it.second <= end }.minByOrNull { it.second } ?: break
                due.remove(next); t = maxOf(t, next.second); next.third()
            }
            t = end
        }
        fun run(untilMs: Long, stop: () -> Boolean) { while (t < untilMs && !stop() && !left) advance(50) }
    }

    private fun start(env: Env): GuardGame {
        val g = GuardGame(env, env, { env.t }, { 0L }, seed = 4)
        env.game = g; g.start(); return g
    }

    private fun frameMs(f: Long) = f * 1000.0 / GuardSynth.SR

    private fun strikes(line: String) = line.substringAfter("strikes=").split(",").filter { it.isNotBlank() }.map { it.toLong() }
    private fun field(line: String, key: String) = line.substringAfter("$key=").substringBefore(" ")

    /** A player who clicks exactly on every strike (plus [delay] ms) and along with the calibration ticks. */
    private fun perfect(delay: Long): (Env, String) -> Unit = { e, line ->
        if (line.startsWith("guard enemy") && field(line, "mode") != "DEMO") {
            for (f in strikes(line)) e.at(maxOf(0L, frameMs(f).toLong() - e.t + delay)) { e.game?.down(e.t); e.game?.up(e.t + 20) }
        }
        if (line.startsWith("guard calibrate start")) {
            val base = field(line, "base").toLong()
            for (tk in GuardCalibration.tickMs) e.at(maxOf(0L, frameMs(base).toLong() + tk.toLong() - e.t + delay)) { e.game?.down(e.t); e.game?.up(e.t + 20) }
        }
    }

    private fun realResults(e: Env) = e.logs.filter { it.startsWith("guard result") }

    @Test fun perfectPlayerFinishesInUnderNinetySecondsAndLosesNothing() {
        val env = Env(perfect(0)); start(env)
        env.run(300_000) { env.taught }
        println("REPORT tutorial perfect player: ${env.taughtAt / 1000.0} s, ${env.spoken.size} lines")
        assertTrue("finished", env.taught)
        assertTrue("under 90 s of audio time: ${env.taughtAt}", env.taughtAt in 1..89_999)
        assertTrue(env.spoken.contains(GuardVoice.LEAVE.text))
        assertEquals(GuardVoice.GATE.text, env.spoken.first())
        assertTrue(realResults(env).isEmpty())     // no hearts at all during the tutorial
        env.run(env.t + 60_000) { false }
        assertTrue("the real game goes on after the tutorial", realResults(env).isNotEmpty())
        assertTrue(realResults(env).none { "hurt=true" in it })
    }

    @Test fun aLaggyPerfectPlayerIsCalibratedByTheTapAlong() {
        val env = Env(perfect(200)); start(env)
        env.run(300_000) { env.taught }
        assertTrue(env.taught)
        assertEquals(200.0, env.bias!!, 50.0)
    }

    @Test fun aPlayerWhoNeverClicksIsToldAndNeverHurt() {
        val env = Env(); start(env)
        env.run(1_500_000) { false }
        assertTrue("told to press", env.spoken.contains(GuardVoice.PRESS.text))
        assertTrue("click-with-ping reminder repeated", env.spoken.count { it == GuardVoice.CLICK_PING.text } >= 2)
        assertFalse(env.taught)
        assertTrue(realResults(env).isEmpty())
        assertTrue("leaves quietly after a very long silence, not before 3 minutes", env.left && env.t > 180_000)
    }

    @Test fun aConfusedRandomClickerGetsThroughWithoutLosingAHeart() {
        for (seed in 1L..8L) {
            val rng = Random(seed)
            val env = Env { e, line ->
                if (line.startsWith("guard enemy")) {
                    val b = field(line, "bpm").toDouble().let { 60000.0 / it }
                    repeat(rng.nextInt(4)) { e.click((rng.nextDouble() * 5.8 * b).toLong() + 250) }
                }
            }
            start(env)
            env.run(1_200_000) { env.taught }
            if (!env.taught) println("DBG seed $seed t=${env.t} " + env.logs.filter { it.startsWith("guard tutorial") }.takeLast(12).joinToString("\n") + "\n" + env.spoken.takeLast(8))
            assertTrue("seed $seed finished", env.taught)
            assertTrue(realResults(env).isEmpty())
        }
    }

    @Test fun aClickDuringTheTalkSkipsToThePractice() {
        val env = Env(); start(env)
        env.advance(300)           // the first line is being spoken
        env.click(0); env.advance(1500)
        assertTrue(env.logs.any { it.startsWith("guard enemy") && field(it, "mode") == "TRY" })
        assertFalse(env.spoken.contains(GuardVoice.CLICK_FOURTH.text))
    }

    @Test fun earlyAndLateHintsAreSpokenOnlyOnce() {
        val tut = GuardTutorial()
        while (true) { val s = tut.next(); if (s is TStep.Try) break }
        repeat(3) { tut.done(TryResult.EARLY); }
        val said = ArrayList<String>()
        repeat(12) { val s = tut.next(); if (s is TStep.Say) said.add(s.line.text) }
        assertEquals(1, said.count { it == GuardVoice.TOO_EARLY.text })
    }

    @Test fun laterRunsSkipTheTutorial() {
        val env = Env(perfect(0)); env.taught = true; env.bias = 0.0
        val g = GuardGame(object : GuardOut by env { override fun best() = 50 }, env, { env.t }, { 0L }, seed = 2)
        env.game = g; g.start()
        env.advance(6000)
        assertEquals(GuardVoice.INTRO.text, env.spoken.first())
        assertTrue(env.logs.any { it.startsWith("guard enemy") && field(it, "mode") == "REAL" })
    }

    @Test fun aNewSoundOutputAsksForTheTapAlongAgain() {
        val env = Env(perfect(0)); env.taught = true; env.bias = null
        val g = GuardGame(object : GuardOut by env { override fun best() = 50 }, env, { env.t }, { 0L }, seed = 2)
        env.game = g; g.start()
        env.run(30_000) { env.logs.any { it.startsWith("guard enemy") } }
        assertTrue(env.logs.any { it.startsWith("guard calibrate start") })
        assertTrue(env.spoken.contains(GuardVoice.CLICK_BEAT.text))
    }
}
