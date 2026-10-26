package com.faka.invisible.guard

import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers
import java.util.Random

/** What the game needs from its surroundings. No Android types, so the whole game runs in unit tests. */
interface GuardOut {
    fun nowFrame(): Long
    fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double)
    fun clearScheduled()
    fun speak(text: Phrase)
    fun stopSpeech()
    fun best(): Int
    fun saveBest(score: Int)
    /** The saved click delay for the current sound output, or null when this output was never calibrated. */
    fun bias(): Double?
    fun saveBias(ms: Double)
    fun taught(): Boolean
    fun saveTaught(v: Boolean)
    fun log(line: String)
    fun leave()
}

/**
 * GUARD: you are the guard at a gate. An enemy is announced, takes three steps, and you click ON the fourth beat.
 * Every sound is scheduled on the audio clock; clicks are timed against that clock (wall time mapped from it).
 * First run: the tutorial ([GuardTutorial]). Later runs: one line, then the first practice enemy.
 */
class GuardGame(
    private val out: GuardOut,
    private val timers: Timers,
    private val now: () -> Long,
    private val latencyMs: () -> Long,
    seed: Long = System.nanoTime(),
) {
    companion object {
        const val LEAVE_HOLD_MS = 3000L
        private const val SR = GuardSynth.SR
        private const val TUTORIAL_BPM = 80.0
        private val TIMER_KEYS = listOf("speech", "go", "fend", "res0", "res1", "say", "next", "over", "leave", "leave-after", "heal")
    }

    private enum class Phase { SPEECH, PLAY, GAP, OVER }
    private enum class Mode { DEMO, TRY, REAL, CALIB }

    private val rng = Random(seed)
    private val judge = GuardJudge(out.bias() ?: 0.0)
    private var run = GuardRun(1)
    private var tut: GuardTutorial? = null
    private var phase = Phase.GAP
    private var mode = Mode.REAL
    private var pending: (() -> Unit)? = null
    private var playDone: ((Outcome?) -> Unit)? = null
    private var enc: GuardJudge.Encounter? = null
    private var startWall = 0.0
    private var calibClicks = ArrayList<Double>()
    private var stopped = false
    private var leaveSaid = false
    private var side = false
    private var missStreak = 0
    private var remindedWave = -1

    fun start() {
        out.log("guard start best=${out.best()} bias=${out.bias()}")
        if (out.taught() && out.best() > 0) {
            leaveSaid = false
            say(GuardVoice.INTRO) { if (out.bias() == null) say(GuardVoice.CLICK_BEAT) { calibrate { enemy() } } else enemy() }
        } else {
            tut = GuardTutorial()
            runTutorial()
        }
    }

    /** The speech engine finished a sentence. */
    fun onSpeechDone() = speechEnded()

    // ---- speech ----
    private fun say(line: Phrase, then: () -> Unit) {
        if (stopped) return
        phase = Phase.SPEECH; pending = then
        out.speak(line)
        timers.set("speech", 1200L + 600L * line.text.split(" ").size) { speechEnded() }
    }

    private fun speechEnded() {
        if (stopped || phase != Phase.SPEECH) return
        timers.clear("speech")
        val p = pending; pending = null
        phase = Phase.GAP
        timers.set("go", 350) { p?.invoke() }
    }

    private fun after(ms: Long, task: () -> Unit) { phase = Phase.GAP; timers.set("go", ms) { task() } }

    // ---- the tutorial ----
    private fun runTutorial() {
        if (stopped) return
        val t = tut ?: return
        when (val s = t.next()) {
            is TStep.Say -> say(s.line) { runTutorial() }
            is TStep.Demo -> play(tutEnemy(s.kind, s.goodMs), Mode.DEMO, s.ping) { after(300) { runTutorial() } }
            is TStep.Try -> play(tutEnemy(s.kind, s.goodMs), Mode.TRY, s.ping) { o ->
                val r = if (o == null) TryResult.NONE else GuardTutorial.classify(o)
                if (o != null) out.saveBias(judge.biasMs)
                if (r == TryResult.SUCCESS && s.kind == Kind.DECOY) out.play(GuardSynth.calm, 0, 0.0, 0.5)
                out.log("guard tutorial try kind=${s.kind} ping=${s.ping} window=${s.goodMs.toInt()} result=$r")
                t.done(r)
                after(if (r == TryResult.SUCCESS) 500 else 350) { runTutorial() }
            }
            TStep.Calibrate -> calibrate { runTutorial() }
            TStep.Finish -> {
                out.saveTaught(true)
                tut = null; run = GuardRun(4); leaveSaid = true
                after(300) { enemy() }
            }
            TStep.Quit -> leave()
        }
    }

    private fun tutEnemy(kind: Kind, goodMs: Double): Enemy {
        side = !side
        val g = if (goodMs <= 0.0) 180.0 else goodMs
        return Enemy(kind, true, TUTORIAL_BPM, g, GuardLadder.perfect(g), side)
    }

    // ---- one enemy on the audio clock ----
    private fun play(e: Enemy, m: Mode, ping: Boolean, done: (Outcome?) -> Unit) {
        if (stopped) return
        phase = Phase.PLAY; mode = m; playDone = done
        val f0 = out.nowFrame(); val w0 = now()
        val marker = m == Mode.REAL && e.practice
        val base = f0 + (if (marker) SR / 2 else SR / 4)
        val pan = if (e.left) -0.8 else 0.8
        val beat = e.beatMs * SR / 1000.0
        fun at(beats: Double) = base + (beats * beat).toLong()
        if (marker) out.play(GuardSynth.practice, f0 + SR / 10, 0.0, 0.3)
        out.play(GuardSynth.announce(e.kind), base, if (e.decoy) 0.0 else pan, 0.6)
        for (b in e.stepBeats) out.play(GuardSynth.step(e.kind), at(b.toDouble()), pan, 0.5)
        e.fakeMs?.let { out.play(GuardSynth.fake, at(3.5), pan, 0.35) }
        if (e.kind == Kind.OGRE) out.play(GuardSynth.ogreStrike, at(4.0), pan, 0.7)
        if (ping || marker) for (b in e.strikeBeats) out.play(GuardSynth.ping, at(b.toDouble()), pan, 0.25)
        if (m == Mode.REAL && run.hearts == 1) {
            var t = 0L
            while (t < e.endMs + 1500) { out.play(GuardSynth.heartbeat, base + t * SR / 1000, 0.0, 0.25); t += 1000 }
        }
        val toWall = { frame: Long -> w0 + (frame - f0) * 1000.0 / SR + latencyMs() }
        startWall = toWall(base)
        if (m == Mode.DEMO) {
            enc = null
            timers.set("fend", (startWall + e.endMs + 250 - now()).toLong().coerceAtLeast(0)) { finishPlay() }
        } else {
            val en = judge.Encounter(e).also { enc = it }
            e.strikesMs.forEachIndexed { i, s ->
                timers.set("res$i", (startWall + s + en.bias + e.goodMs - now()).toLong().coerceAtLeast(0)) {
                    if (phase == Phase.PLAY && !en.matched(i)) { en.miss(i); out.play(GuardSynth.thud, 0, 0.0, 0.7) }
                }
            }
            timers.set("fend", (startWall + e.endMs + en.bias + 60 - now()).toLong().coerceAtLeast(0)) { finishPlay() }
        }
        out.log("guard enemy kind=${e.kind} practice=${e.practice} mode=$m bpm=${e.bpm.toInt()} good=${e.goodMs.toInt()} side=${if (e.left) "L" else "R"} bias=${judge.biasMs.toInt()} strikes=${e.strikeBeats.joinToString(",") { at(it.toDouble()).toString() }}")
    }

    private fun finishPlay() {
        if (stopped || phase != Phase.PLAY) return
        phase = Phase.GAP
        val o = enc?.finish(); enc = null
        val cb = playDone; playDone = null
        cb?.invoke(o)
    }

    // ---- tap-along calibration ----
    private fun calibrate(then: () -> Unit) {
        if (stopped) return
        phase = Phase.PLAY; mode = Mode.CALIB
        calibClicks = ArrayList()
        val f0 = out.nowFrame(); val w0 = now()
        val base = f0 + SR / 2
        val ticks = GuardCalibration.tickMs
        for (t in ticks) out.play(GuardSynth.tick, base + (t * SR / 1000).toLong(), 0.0, 0.45)
        startWall = w0 + (base - f0) * 1000.0 / SR + latencyMs()
        out.log("guard calibrate start base=$base")
        timers.set("fend", (startWall + ticks.last() + 700 - now()).toLong().coerceAtLeast(0)) {
            if (!stopped && phase == Phase.PLAY && mode == Mode.CALIB) {
                phase = Phase.GAP
                val off = GuardCalibration.offset(ticks, calibClicks)
                if (off != null) judge.setBias(off)
                out.saveBias(judge.biasMs)
                out.log("guard calibrate clicks=${calibClicks.size} offset=$off")
                after(300, then)
            }
        }
    }

    // ---- the real game ----
    private fun introLine(): Phrase? = if (!run.stageStart) null else when (run.stage) {
        2 -> GuardVoice.KNIGHT; 3 -> GuardVoice.BELL; 4 -> GuardVoice.FAST; 5 -> GuardVoice.OGRE; else -> null
    }

    private fun enemy() {
        if (stopped) return
        val e = run.next(rng)
        val line = introLine()
        if (line != null) say(line) { fight(e) } else fight(e)
    }

    private fun fight(e: Enemy) = play(e, Mode.REAL, false) { o -> settle(o ?: return@play) }

    private fun settle(o: Outcome) {
        out.saveBias(judge.biasMs)
        val before = run.hearts
        run.record(o)
        if (o.decoyOk) out.play(GuardSynth.calm, 0, 0.0, 0.5)
        if (run.healed) timers.set("heal", 350) { out.play(GuardSynth.heal, 0, 0.0, 0.5) }
        if (!o.practice) missStreak = if (o.hurt) missStreak + 1 else 0
        out.log("guard result kind=${o.kind} practice=${o.practice} hurt=${o.hurt} perfect=${o.perfects} good=${o.goods} hearts=${run.hearts} score=${run.score} stage=${run.stage}")
        if (run.over) { timers.set("over", 800) { gameOver() }; return }
        val line = when {
            !leaveSaid && run.encounters >= 3 -> { leaveSaid = true; GuardVoice.LEAVE }
            run.hearts == 1 && before > 1 -> GuardVoice.LAST_LIFE
            missStreak >= 2 && remindedWave != run.waveId -> { remindedWave = run.waveId; missStreak = 0; GuardVoice.CLICK_FOURTH }
            else -> null
        }
        if (line != null) timers.set("say", 600) { say(line) { enemy() } } else timers.set("next", 900) { enemy() }
    }

    private fun gameOver() {
        phase = Phase.OVER
        val best = out.best()
        out.play(GuardSynth.over, 0, 0.0, 0.5)
        out.speak(GuardVoice.over(run.score, best))
        if (run.score > best) out.saveBest(run.score)
        if (run.encounters <= 12) out.saveTaught(false)   // lost everything in the first wave: teach again next time
        timers.set("leave-after", 6000) { leave() }
    }

    // ---- the button ----
    fun down(time: Long) {
        if (stopped) return
        if (phase == Phase.OVER) { leave(); return }
        timers.set("leave", LEAVE_HOLD_MS) { leave() }
        when (phase) {
            Phase.SPEECH -> {   // a click interrupts the talking and moves on to the practice
                timers.clear("speech")
                val p = pending; pending = null
                phase = Phase.GAP
                out.stopSpeech()
                tut?.skipToTry()
                timers.set("go", 250) { p?.invoke() }
            }
            Phase.PLAY -> when (mode) {
                Mode.DEMO -> {
                    timers.clear("fend")
                    out.clearScheduled()
                    tut?.skipToTry()
                    val cb = playDone; playDone = null
                    phase = Phase.GAP
                    timers.set("go", 250) { cb?.invoke(null) }
                }
                Mode.CALIB -> { calibClicks.add(time - startWall); out.play(GuardSynth.tap, 0, 0.0, 0.5) }
                else -> {
                    val v = enc?.click(time - startWall) ?: return
                    val s = when (v) {
                        Verdict.PERFECT -> GuardSynth.perfect
                        Verdict.GOOD -> GuardSynth.good
                        Verdict.EARLY, Verdict.STRAY -> GuardSynth.whoosh
                        Verdict.BAIT -> if (mode == Mode.TRY) GuardSynth.whoosh else GuardSynth.thud
                        Verdict.AFTER -> null
                    }
                    if (s != null) out.play(s, 0, 0.0, 0.6)
                }
            }
            else -> out.play(GuardSynth.whoosh, 0, 0.0, 0.4)   // a click with nothing coming costs nothing
        }
    }

    fun up(time: Long) { timers.clear("leave") }

    private fun leave() {
        if (stopped) return
        stopped = true
        out.saveBias(judge.biasMs)
        for (k in TIMER_KEYS) timers.clear(k)
        out.stopSpeech()
        out.leave()
    }

    /** The app stops the game from outside. */
    fun stop() = leave()
}
