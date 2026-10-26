package com.faka.invisible.practice

import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.GestureRules
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit

/**
 * First-run practice (about 60 s). Teaches by doing: click once, twice, three times, then click-then-hold (the OS catches
 * that one and stops the program; reaching it is the last step). Each success has its own earcon. Hold 3 s skips.
 * Runs by itself the first time the OS is woken; the Help page replays it. [DONE_KEY] in the prefs says it ran.
 */
class PracticeProgram : Program {
    private lateinit var host: ProgramHost
    private var running = false
    private var step = PracticeStep.ONE
    private var presses = ArrayList<Pair<Long, Long>>()
    private var downAt = 0L
    private var idle = 0
    private val leave = Runnable { finish() }
    private val resolve = Runnable { val g = GestureRules.classify(presses); presses = ArrayList(); if (running) onGesture(g) }
    private val nudge = Runnable { if (running) repeat() }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        running = true
        host.prefs.edit().putBoolean(DONE_KEY, true).apply()   // it ran once: never again by itself
        host.pauseMusic()
        host.speak(PracticeVoice.INTRO, true)
    }

    override fun stop() = finish()

    override fun down(time: Long) {
        downAt = time
        host.cancel(resolve); host.cancel(nudge); host.stopSpeech()
        host.later(leave, LEAVE_MS)
    }

    override fun up(time: Long) {
        host.cancel(leave)
        if (time - downAt >= LEAVE_MS) return
        presses.add(downAt to time)
        host.later(resolve, Pacing.GAP_MS)
    }

    override fun speechDone() { if (running) { host.cancel(nudge); host.later(nudge, IDLE_MS) } }

    private fun onGesture(g: Gesture?) {
        if (!PracticeCore.passes(step, g)) { repeat(); return }
        idle = 0
        VoiceKit.narrator.beep(Tone.PICK)
        val n = PracticeCore.next(step, g) ?: return
        step = n
        host.speak(when (n) { PracticeStep.TWO -> PracticeVoice.TWO; PracticeStep.THREE -> PracticeVoice.THREE; else -> PracticeVoice.CLOSE }, true)
    }

    private fun repeat() {
        if (++idle > PracticeCore.MAX_IDLE) { finish(); return }
        host.speak(when (step) { PracticeStep.ONE -> PracticeVoice.AGAIN_ONE; PracticeStep.TWO -> PracticeVoice.AGAIN_TWO; PracticeStep.THREE -> PracticeVoice.AGAIN_THREE; PracticeStep.CLOSE -> PracticeVoice.AGAIN_CLOSE }, true)
    }

    private fun finish() {
        if (!running) return
        running = false
        host.cancel(leave); host.cancel(resolve); host.cancel(nudge)
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    companion object {
        const val DONE_KEY = "practice_done"
        private const val LEAVE_MS = 3000L
        private const val IDLE_MS = 10000L   // quiet after a prompt before it is repeated: time to find the button and press
    }
}

object PracticeEntry {
    val entry = ProgramEntry("tutorial:practice", PracticeVoice.TITLE) { PracticeProgram() }
}
