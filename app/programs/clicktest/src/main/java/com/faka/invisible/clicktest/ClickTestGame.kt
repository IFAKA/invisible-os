package com.faka.invisible.clicktest

import com.faka.invisible.logickit.ClickProfile
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers
import kotlin.math.roundToInt
import kotlin.random.Random

/** What the game needs from the phone (the Android side implements it; tests fake it). */
interface ClickTestOut {
    /** Frames of sound already handed to the audio output: the AUDIO clock. */
    fun nowFrame(): Long
    fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double)
    fun speak(text: Phrase)
    fun stopSpeech()
    fun log(line: String)
    fun save(json: String)
    fun leave()
}

/**
 * CLICK TEST. A steady beat of short sounds (one every 350 ms); one of them is the high bell. The person clicks when they hear it.
 * Click time minus the bell's real onset = how late they click. The onset is the frame the bell was scheduled at on the audio
 * clock, turned to wall time with the measured output delay; never the time the code asked to play.
 * Two slow rounds (700 ms) warm up (not counted), then 350 ms rounds until [GOAL] clicks are measured or 5 minutes pass.
 */
class ClickTestGame(
    private val out: ClickTestOut,
    private val timers: Timers,
    private val now: () -> Long,
    private val latencyMs: () -> Long,
    seed: Long = System.nanoTime(),
) {
    companion object {
        const val LEAVE_HOLD_MS = 3000L
        const val GOAL = 60
        const val LIMIT_MS = 5 * 60 * 1000L
        const val WARM_ROUNDS = 2
        const val SLOW_MS = 700
        const val FAST_MS = 350
        private const val ITEMS = 16
        private const val TARGETS = 3
        private const val EARLY_MS = 120.0
        private const val LEAD_FRAMES = ClickTestSynth.SR / 4
        private const val DEMO_FRAMES = ClickTestSynth.SR * 6 / 5
    }

    private val rng = Random(seed)
    private var stopped = false
    private var introDone = false
    private var playing = false
    private var round = 0
    private var silentRounds = 0
    private var startedWall = 0L
    private val offsets = ArrayList<Double>()     // counted: fast rounds only
    private var roundHits = 0
    private var stepMs = SLOW_MS
    private var onsets = DoubleArray(0)           // wall ms of each bell of this round
    private var clicked = BooleanArray(0)

    fun start() {
        out.log("clicktest start")
        out.speak(ClickTestVoice.INTRO)
        timers.set("intro-fallback", 7000) { onSpeechDone() }
    }

    /** The speech engine finished a sentence. Only the intro waits for it. */
    fun onSpeechDone() {
        if (stopped || introDone) return
        introDone = true
        timers.clear("intro-fallback")
        startedWall = now()
        nextRound()
    }

    private fun spots(): List<Int> {
        while (true) {
            val s = List(TARGETS) { rng.nextInt(3, ITEMS) }.sorted()
            if (s.zipWithNext().all { it.second - it.first >= 3 }) return s
        }
    }

    private fun nextRound() {
        if (stopped) return
        stepMs = if (round < WARM_ROUNDS) SLOW_MS else FAST_MS
        val step = stepMs * ClickTestSynth.SR / 1000L
        val f0 = out.nowFrame(); val w0 = now()
        val toWall = { frame: Long -> w0 + (frame - f0) * 1000.0 / ClickTestSynth.SR + latencyMs() }
        val base = f0 + LEAD_FRAMES
        out.play(ClickTestSynth.target, base, 0.0, 0.5)          // the demo: this is the one
        val first = base + DEMO_FRAMES
        val spot = spots()
        onsets = DoubleArray(TARGETS); clicked = BooleanArray(TARGETS); roundHits = 0
        var last = -1
        for (k in 0 until ITEMS) {
            val at = first + k * step
            val ti = spot.indexOf(k)
            if (ti >= 0) {
                out.play(ClickTestSynth.target, at, 0.0, 0.5)
                onsets[ti] = toWall(at)
                val i = ti
                timers.set("miss$i", (onsets[i] + window() - now()).toLong().coerceAtLeast(0)) { if (!clicked[i]) out.play(ClickTestSynth.miss, 0, 0.0, 0.3) }
            } else {
                var o: Int
                do { o = rng.nextInt(ClickTestSynth.others.size) } while (o == last)
                last = o
                out.play(ClickTestSynth.others[o], at, 0.0, 0.5)
            }
        }
        val endWall = toWall(first + ITEMS * step) + 150
        timers.set("end", (endWall - now()).toLong().coerceAtLeast(0)) { finishRound() }
        playing = true
        out.log("clicktest round ${round + 1} step=${stepMs}ms counted=${offsets.size}")
    }

    private fun window() = stepMs * 0.9

    private fun finishRound() {
        if (stopped) return
        playing = false
        round++
        silentRounds = if (roundHits == 0) silentRounds + 1 else 0
        when {
            silentRounds >= 3 -> end(null)
            offsets.size >= GOAL || now() - startedWall >= LIMIT_MS -> end(ClickProfile.of(offsets))
            else -> timers.set("next", 600) { nextRound() }
        }
    }

    /** Button pressed: this is the click. */
    fun down(time: Long) {
        if (stopped) return
        timers.set("leave", LEAVE_HOLD_MS) { leave() }
        if (!playing) return
        // the bell this click answers: the latest one that began no more than a window ago, and is not yet answered
        var best = -1
        for (i in onsets.indices) {
            val d = time - onsets[i]
            if (!clicked[i] && d >= -EARLY_MS && d <= window() && (best < 0 || onsets[i] > onsets[best])) best = i
        }
        if (best < 0) { out.play(ClickTestSynth.miss, 0, 0.0, 0.3); out.log("clicktest click off-target"); return }
        clicked[best] = true; roundHits++
        val off = time - onsets[best]
        val counted = stepMs == FAST_MS
        if (counted) offsets.add(off)
        out.play(ClickTestSynth.hit, 0, 0.0, 0.5)
        out.log("clicktest click offset=${off.roundToInt()}ms${if (counted) "" else " (warm-up)"} n=${offsets.size}")
    }

    fun up(time: Long) { timers.clear("leave") }

    private fun end(profile: ClickProfile?) {
        playing = false
        for (k in listOf("next", "end", "leave") + (0 until TARGETS).map { "miss$it" }) timers.clear(k)
        when {
            profile == null -> out.speak(ClickTestVoice.NO_CLICKS)
            profile.count < ClickProfile.MIN_COUNT -> out.speak(ClickTestVoice.TOO_FEW)
            else -> {
                out.save(profile.toJson())
                out.log("clicktest profile mean=${profile.meanMs.roundToInt()}ms sd=${profile.sdMs.roundToInt()}ms n=${profile.count}")
                out.play(ClickTestSynth.done, 0, 0.0, 0.5)
                out.speak(ClickTestVoice.lateness(profile.meanMs.roundToInt()))
                out.speak(ClickTestVoice.spread(profile.sdMs.roundToInt()))
                out.speak(ClickTestVoice.SAVED)
            }
        }
        timers.set("close", 6000) { leave() }
    }

    private fun leave() {
        if (stopped) return
        stopped = true
        playing = false
        timers.clear("close")
        out.leave()
    }

    fun stop() { stopped = true; playing = false }
}
