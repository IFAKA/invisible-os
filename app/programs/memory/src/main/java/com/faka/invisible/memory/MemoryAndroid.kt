package com.faka.invisible.memory

import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.GameSounds
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Synth

/**
 * Memory. The phone plays a sequence of low and high tones (low in the left ear, high in the right); you copy it:
 * one click for a low tone, two clicks for a high tone. Each round adds one tone. A wrong tone ends the game with your score.
 * Hold 3 s leaves. No microphone: only the button.
 */
class MemoryAndroid : Program {
    private enum class State { LOADING, INTRO, LISTEN, INPUT, OVER, DONE }

    private lateinit var host: ProgramHost
    private val sounds = GameSounds()
    private var core = MemoryCore()
    private var state = State.LOADING
    private var clicks = 0
    private var downAt = 0L
    private val leave = Runnable { shutdown() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (state == State.INPUT) onClicks(n) }
    private val turn = Runnable { if (state == State.LISTEN) { state = State.INPUT; sounds.play(MemorySynth.go, 0.0, 0.25) } }
    private val fallback = Runnable { if (state == State.OVER) shutdown() }

    override val active get() = state != State.DONE

    override fun start(host: ProgramHost) {
        if (state != State.LOADING) return
        this.host = host
        host.pauseMusic()
        val w = host.waiting()
        sounds.warm(host.handler, { MemorySynth.warm() }) {
            w()
            if (state == State.LOADING) begin()
        }
    }

    private fun begin() {
        if (!sounds.open()) { host.speak(MemoryVoice.LOADING); shutdown(); return }
        state = State.INTRO
        core = MemoryCore()
        Trace.ev("program", "memory start")
        host.speak(Phrase.join(listOf(MemoryVoice.INTRO, MemoryVoice.HOW, MemoryVoice.LEAVE)))
    }

    override fun speechDone() {
        when (state) {
            State.INTRO -> nextRound()
            State.OVER -> shutdown()
            else -> {}
        }
    }

    private fun nextRound() {
        val seq = core.newRound()
        state = State.LISTEN
        clicks = 0
        seq.forEachIndexed { i, s -> sounds.play(MemorySynth.of(s), MemorySynth.pan(s), 0.5, 500L + i * STEP_MS) }
        host.later(turn, 500L + seq.size * STEP_MS + 100)
    }

    override fun down(time: Long) { downAt = time; host.cancel(resolve); host.later(leave, Pacing.LEAVE_MS) }

    override fun up(time: Long) {
        host.cancel(leave)
        if (state == State.INPUT && time - downAt < Pacing.HOLD_MS) { clicks++; host.later(resolve, Pacing.GAP_MS) }
    }

    private fun onClicks(n: Int) {
        val sym = MemoryCore.symbolOf(n)
        if (sym != null) sounds.play(MemorySynth.of(sym), MemorySynth.pan(sym), 0.5)
        when (core.answer(sym)) {
            MemoryCore.Step.RIGHT -> {}
            MemoryCore.Step.ROUND_DONE -> {
                Trace.ev("program", "memory round ${core.score} ok")
                state = State.LISTEN
                sounds.play(MemorySynth.cleared, 0.0, 0.4, 300)
                host.later({ if (state == State.LISTEN) host.speak(MemoryVoice.ROUND_OK) }, 900)
                host.later({ if (state == State.LISTEN) nextRound() }, 2300)
            }
            MemoryCore.Step.WRONG -> {
                state = State.OVER
                val best = host.prefs.getInt("memory_best", 0)
                if (core.score > best) host.prefs.edit().putInt("memory_best", core.score).apply()
                Trace.ev("program", "memory over score ${core.score} wrong clicks $n")
                sounds.play(MemorySynth.failed, 0.0, 0.5)
                host.later({ if (state == State.OVER) host.speak(MemoryVoice.over(core.score, maxOf(best, core.score))) }, 700)
                host.later(fallback, 9000)
            }
        }
    }

    override fun stop() = shutdown()

    private fun shutdown() {
        if (state == State.DONE) return
        val was = state
        state = State.DONE
        host.cancel(leave); host.cancel(resolve); host.cancel(turn); host.cancel(fallback)
        host.stopSpeech()
        if (was != State.LOADING) sounds.close()
        host.resumeMusic()
        host.ended()
    }

    private companion object { const val STEP_MS = 650L }
}

/** The sounds of Memory (pure maths). Low and high are always the same two notes. */
object MemorySynth {
    val low by lazy { Synth.tone(262.0, 380, 220.0, 0.2) }
    val high by lazy { Synth.tone(784.0, 380, 220.0, 0.2) }
    val go by lazy { Synth.tone(1200.0, 40, 12.0) }
    val cleared by lazy { Synth.then(Synth.tone(659.0, 180, 80.0), Synth.tone(880.0, 260, 110.0), 130) }
    val failed by lazy { Synth.then(Synth.tone(247.0, 220, 100.0), Synth.tone(165.0, 380, 160.0), 150) }
    fun of(sym: Int) = if (sym == 0) low else high
    fun pan(sym: Int) = if (sym == 0) -0.6 else 0.6
    fun warm() { low; high; go; cleared; failed }
}

/** What the OS registry lists for Memory. */
object MemoryProgram {
    val entry = ProgramEntry("games:memory", MemoryVoice.TITLE) { MemoryAndroid() }
}
