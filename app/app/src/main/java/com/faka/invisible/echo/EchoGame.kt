package com.faka.invisible.echo

import com.faka.invisible.voicekit.EchoVoice
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers
import java.util.Random

/** What the game needs from its surroundings. No Android types, so the whole game runs in unit tests. */
interface EchoOut {
    fun nowFrame(): Long
    fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double)
    fun speak(text: Phrase)
    fun stopSpeech()
    fun best(): Int
    fun saveBest(score: Int)
    fun log(line: String)
    fun leave()
}

/**
 * ECHO: a round is a count-in, the robot's rhythm (low ticks, wood block on the left), then your turn (high ticks):
 * click the same rhythm. Every sound is scheduled on the audio clock; clicks are timed against that clock.
 */
class EchoGame(
    private val out: EchoOut,
    private val timers: Timers,
    private val now: () -> Long,
    private val latencyMs: () -> Long,
    private val seed: Long = System.nanoTime(),
) {
    companion object {
        const val LEAVE_HOLD_MS = 3000L
        private const val LEAD_FRAMES = EchoSynth.SR / 4
        private const val ROBOT_PAN = -0.9
        private const val ROBOT_DB = 0.55
    }

    private enum class Phase { INTRO, LISTEN, YOURS, RESULT, OVER }
    private val rng = Random(seed)
    private val judge = EchoJudge()
    private val run = EchoRun()
    private var phase = Phase.INTRO
    private var level = EchoLadder.level(1)
    private var pattern = listOf<Int>()
    private var bar: EchoJudge.Bar? = null
    private var yourStartWall = 0.0
    private var yourStartFrame = 0L
    private var downAt = -1L
    private var stopped = false
    private var introDone = false

    fun start() {
        out.log("echo start best=${out.best()}")
        phase = Phase.INTRO
        out.speak(EchoVoice.INTRO)
        timers.set("intro-fallback", 7000) { onSpeechDone() }
    }

    /** The speech engine finished a sentence. Only the intro waits for it. */
    fun onSpeechDone() {
        if (stopped || phase != Phase.INTRO || introDone) return
        introDone = true
        timers.clear("intro-fallback")
        round()
    }

    private fun sec(slots: Int, l: EchoLevel = level) = (slots * l.slotMs * EchoSynth.SR / 1000.0).toLong()

    private fun round() {
        if (stopped) return
        level = EchoLadder.level(run.level)
        pattern = EchoLadder.pattern(level, rng)
        val slot = sec(1)
        val f0 = out.nowFrame()
        val w0 = now()
        val base = f0 + LEAD_FRAMES
        fun pulse(at: Long, accent: Boolean, high: Boolean) {
            val s = if (high) (if (accent) EchoSynth.tockAccent else EchoSynth.tock) else (if (accent) EchoSynth.tickAccent else EchoSynth.tick)
            out.play(s, at, 0.0, 0.32)
        }
        // count-in: one bar of beats (every second slot)
        for (k in 0 until 4) pulse(base + 2 * k * slot, k == 0, false)
        val robotStart = base + 8 * slot
        for (s in 0 until level.slots step 2) pulse(robotStart + s * slot, s % 8 == 0, false)
        for (s in pattern) out.play(EchoSynth.robot, robotStart + s * slot, ROBOT_PAN, ROBOT_DB)
        yourStartFrame = robotStart + level.slots * slot
        if (level.guide) for (s in 0 until level.slots step 2) pulse(yourStartFrame + s * slot, s % 8 == 0, true)
        else pulse(yourStartFrame, true, true)       // without a guide you still hear where your turn starts
        val toWall = { frame: Long -> w0 + (frame - f0) * 1000.0 / EchoSynth.SR + latencyMs() }
        yourStartWall = toWall(yourStartFrame)
        val endWall = toWall(yourStartFrame + level.slots * slot)
        bar = judge.Bar(level, pattern)
        phase = Phase.LISTEN
        timers.set("yours", (toWall(yourStartFrame) - now() - level.goodMs).toLong().coerceAtLeast(0)) { if (phase == Phase.LISTEN) phase = Phase.YOURS }
        timers.set("end", (endWall - now() + level.goodMs + 80).toLong().coerceAtLeast(0)) { finishBar() }
        out.log("echo round level=${level.no} bpm=${level.bpm} hits=${pattern.size} bars=${level.bars} bias=${judge.biasMs.toInt()}")
    }

    /** Button pressed: this is the click. */
    fun down(time: Long) {
        if (stopped) return
        if (phase == Phase.OVER) { leave(); return }
        downAt = time
        timers.set("leave", LEAVE_HOLD_MS) { leave() }
        if (phase != Phase.YOURS) return
        val ms = time - yourStartWall
        val g = bar?.click(ms)
        val sound = when (g) { Grade.PERFECT -> EchoSynth.perfect; Grade.GOOD -> EchoSynth.good; null -> EchoSynth.extra }
        out.play(sound, 0, 0.5, 0.6)
    }

    fun up(time: Long) { timers.clear("leave"); downAt = -1 }

    private fun finishBar() {
        if (stopped || phase != Phase.YOURS && phase != Phase.LISTEN) return
        val r = bar?.finish() ?: return
        phase = Phase.RESULT
        val before = run.level
        run.record(r)
        val sound = if (r.perfect) EchoSynth.flawless else if (r.cleared) EchoSynth.cleared else EchoSynth.failed
        out.play(sound, 0, 0.0, 0.5)
        out.log("echo bar level=$before cleared=${r.cleared} perfect=${r.perfect} extras=${r.extras} missed=${r.hits.count { it.grade == null }} hearts=${run.hearts} score=${run.score}")
        if (run.over) { timers.set("over", 900) { gameOver() }; return }
        if (!r.cleared) timers.set("say", 700) { out.speak(EchoVoice.AGAIN) }
        else if (run.level % 5 == 0) timers.set("say", 700) { out.speak(EchoVoice.level(run.level)) }
        timers.set("next", if (r.cleared && run.level % 5 != 0) 1000 else 2200) { round() }
    }

    private fun gameOver() {
        phase = Phase.OVER
        val best = out.best()
        out.play(EchoSynth.over, 0, 0.0, 0.5)
        out.speak(EchoVoice.over(run.score, best))
        if (run.score > best) out.saveBest(run.score)
        timers.set("leave-after", 6000) { leave() }
    }

    private fun leave() {
        if (stopped) return
        stopped = true
        for (k in listOf("intro-fallback", "yours", "end", "say", "next", "over", "leave", "leave-after")) timers.clear(k)
        out.stopSpeech()
        out.leave()
    }

    /** The app stops the game from outside. */
    fun stop() = leave()

    /** After the game-over line, any press leaves. */
    val finished get() = phase == Phase.OVER
}
