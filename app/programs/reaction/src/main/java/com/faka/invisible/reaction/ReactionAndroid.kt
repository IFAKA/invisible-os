package com.faka.invisible.reaction

import android.os.SystemClock
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.GameSounds
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Synth

/**
 * Reaction. After a random wait a beep plays; click as fast as you can. Five rounds; the average time is spoken.
 * A click before the beep is a fault: the round is played again. The time includes the delay of the earbuds.
 * Hold 3 s leaves. No microphone: only the button.
 */
class ReactionAndroid : Program {
    private enum class State { LOADING, INTRO, WAITING, CUE, SHOWN, FAULT, OVER, DONE }

    private lateinit var host: ProgramHost
    private val sounds = GameSounds()
    private var core = ReactionCore()
    private var state = State.LOADING
    private var cueAt = 0L
    private var cutAt = 0L
    private val leave = Runnable { shutdown() }
    private val cue = Runnable { if (state == State.WAITING) beep() }
    private val slow = Runnable { if (state == State.CUE) hit(ReactionCore.SLOW_MS, late = true) }
    private val fallback = Runnable { if (state == State.OVER) shutdown() }

    override val active get() = state != State.DONE

    override fun start(host: ProgramHost) {
        if (state != State.LOADING) return
        this.host = host
        host.pauseMusic()
        val w = host.waiting()
        sounds.warm(host.handler, { ReactionSynth.warm() }) {
            w()
            if (state == State.LOADING) begin()
        }
    }

    private fun begin() {
        if (!sounds.open()) { host.speak(ReactionVoice.LOADING); shutdown(); return }
        state = State.INTRO
        core = ReactionCore()
        Trace.ev("program", "reaction start")
        host.speak(Phrase.join(listOf(ReactionVoice.INTRO, ReactionVoice.LEAVE)))
    }

    override fun speechDone() {
        when (state) {
            State.INTRO, State.FAULT -> waitForBeep()
            // a late "done" from speech we cut off must not start the next round too soon
            State.SHOWN -> if (SystemClock.uptimeMillis() - cutAt > 250) { if (core.done) finish() else waitForBeep() }
            State.OVER -> shutdown()
            else -> {}
        }
    }

    private fun waitForBeep() {
        state = State.WAITING
        host.later(cue, core.waitMs())
    }

    private fun beep() {
        state = State.CUE
        cueAt = SystemClock.uptimeMillis()
        sounds.play(ReactionSynth.beep, 0.0, 0.6)
        host.later(slow, ReactionCore.SLOW_MS.toLong())
    }

    override fun down(time: Long) {
        host.cancel(leave); host.later(leave, Pacing.LEAVE_MS)
        when (state) {
            State.WAITING -> {
                host.cancel(cue)
                core.fault()
                state = State.FAULT
                Trace.ev("program", "reaction fault early")
                sounds.play(ReactionSynth.buzz, 0.0, 0.5)
                host.later({ if (state == State.FAULT) host.speak(ReactionVoice.EARLY) }, 400)
            }
            State.CUE -> hit((SystemClock.uptimeMillis() - cueAt).toInt(), late = false)
            else -> {}
        }
    }

    override fun up(time: Long) { host.cancel(leave) }

    private fun hit(ms: Int, late: Boolean) {
        host.cancel(slow)
        core.record(ms)
        Trace.ev("program", "reaction round ${core.times.size} ${ms}ms")
        state = State.SHOWN
        cutAt = SystemClock.uptimeMillis()
        host.speak(if (late) ReactionVoice.TOO_SLOW else ReactionVoice.time(ms))
    }

    private fun finish() {
        state = State.OVER
        val avg = core.average()
        val best = host.prefs.getInt("reaction_best", 0)
        if (best == 0 || avg < best) host.prefs.edit().putInt("reaction_best", avg).apply()
        Trace.ev("program", "reaction over avg ${avg}ms faults ${core.faults}")
        host.speak(ReactionVoice.over(avg, best))
        host.later(fallback, 9000)
    }

    override fun stop() = shutdown()

    private fun shutdown() {
        if (state == State.DONE) return
        val was = state
        state = State.DONE
        host.cancel(leave); host.cancel(cue); host.cancel(slow); host.cancel(fallback)
        host.stopSpeech()
        if (was != State.LOADING) sounds.close()
        host.resumeMusic()
        host.ended()
    }
}

/** The sounds of Reaction (pure maths). The beep is always the same: short and high. */
object ReactionSynth {
    val beep by lazy { Synth.tone(1320.0, 160, 140.0, 0.1) }
    val buzz by lazy { Synth.tone(110.0, 260, 120.0, 0.9) }
    fun warm() { beep; buzz }
}

/** What the OS registry lists for Reaction. */
object ReactionProgram {
    val entry = ProgramEntry("games:reaction", ReactionVoice.TITLE) { ReactionAndroid() }
}
