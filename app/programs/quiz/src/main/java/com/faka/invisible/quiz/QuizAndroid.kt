package com.faka.invisible.quiz

import android.os.SystemClock
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.GameSounds
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Synth
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone

/**
 * Quiz. Ten short general-knowledge questions. Each is read, then its three answers are read one after the other
 * (the kit Scanner); a click picks the answer you just heard. A sound tells right from wrong. Score at the end.
 * Hold 3 s leaves. No microphone: only the button.
 */
class QuizAndroid : Program {
    private enum class State { LOADING, INTRO, ASKING, SCANNING, ANSWERED, OVER, DONE }

    private lateinit var host: ProgramHost
    private val sounds = GameSounds()
    private var state = State.LOADING
    private var asked: List<Asked> = emptyList()
    private var n = 0
    private var score = 0
    private var downAt = 0L
    private var answeredAt = 0L
    private val keyed = HashMap<String, Runnable>()
    private val leave = Runnable { shutdown() }

    private val timers = object : Timers {
        override fun set(key: String, delayMs: Long, task: () -> Unit) {
            clear(key)
            val r = Runnable { keyed.remove(key); task() }
            keyed[key] = r; host.later(r, delayMs)
        }
        override fun clear(key: String) { keyed.remove(key)?.let { host.cancel(it) } }
    }

    private val out = object : ScanOut {
        override fun say(index: Int, label: Phrase, spearcon: Boolean) { host.speak(label) }
        override fun sayFast(word: Phrase) { host.speak(word) }
        override fun tone(t: Tone) {}
        override fun leave() { answer(-1) }   // the list was read twice with no click: no answer
    }
    private val scanner = Scanner<Phrase>(timers, { it }, out, ScanCadence(pauseMs = { Pacing.ITEM_GAP_MS }, loops = { 2 }, ceremony = false))

    override val active get() = state != State.DONE

    override fun start(host: ProgramHost) {
        if (state != State.LOADING) return
        this.host = host
        host.pauseMusic()
        val w = host.waiting()
        sounds.warm(host.handler, { QuizSynth.warm() }) {
            w()
            if (state == State.LOADING) begin()
        }
    }

    private fun begin() {
        if (!sounds.open()) { host.speak(QuizVoice.LOADING); shutdown(); return }
        state = State.INTRO
        asked = QuizCore.pick(java.util.Random())
        Trace.ev("program", "quiz start")
        host.speak(Phrase.join(listOf(QuizVoice.INTRO, QuizVoice.HOW, QuizVoice.LEAVE)))
    }

    override fun speechDone() {
        when (state) {
            State.INTRO -> ask()
            State.ASKING -> {
                state = State.SCANNING
                val q = asked[n]
                scanner.load(q.choices.map { Phrase.content(it) })
                scanner.speakItemIn(300, 0)
            }
            State.SCANNING -> scanner.itemDone()
            // a late "done" from the speech we just cut off must not skip the verdict
            State.ANSWERED -> if (SystemClock.uptimeMillis() - answeredAt > 250) { if (n < asked.size) ask() else finish() }
            State.OVER -> shutdown()
            else -> {}
        }
    }

    private fun ask() {
        state = State.ASKING
        host.speak(Phrase.content(asked[n].text))
    }

    override fun down(time: Long) { downAt = time; host.cancel(leave); host.later(leave, Pacing.LEAVE_MS) }

    /** A short press while the answers are read picks the one just heard. */
    override fun up(time: Long) {
        host.cancel(leave)
        if (state == State.SCANNING && time - downAt < Pacing.HOLD_MS) answer(scanner.list.index)
    }

    private fun answer(picked: Int) {
        if (state != State.SCANNING) return
        scanner.stop()
        host.stopSpeech()
        val q = asked[n]
        n++
        state = State.ANSWERED
        answeredAt = SystemClock.uptimeMillis()
        if (picked == q.answer) {
            score++
            sounds.play(QuizSynth.right, 0.0, 0.5)
            host.speak(QuizVoice.RIGHT)
        } else {
            sounds.play(QuizSynth.wrong, 0.0, 0.5)
            host.speak(if (picked < 0) Phrase.join(listOf(QuizVoice.TOO_SLOW, QuizVoice.wrong(Phrase.content(q.choices[q.answer])))) else QuizVoice.wrong(Phrase.content(q.choices[q.answer])))
        }
        Trace.ev("program", "quiz q$n ${if (picked == q.answer) "right" else "wrong"}")
    }

    private fun finish() {
        state = State.OVER
        val best = host.prefs.getInt("quiz_best", 0)
        if (score > best) host.prefs.edit().putInt("quiz_best", score).apply()
        Trace.ev("program", "quiz over score $score")
        sounds.play(QuizSynth.done, 0.0, 0.4)
        host.speak(QuizVoice.over(score, maxOf(best, score)))
    }

    override fun stop() = shutdown()

    private fun shutdown() {
        if (state == State.DONE) return
        val was = state
        state = State.DONE
        host.cancel(leave)
        scanner.stop()
        host.stopSpeech()
        if (was != State.LOADING) sounds.close()
        host.resumeMusic()
        host.ended()
    }
}

/** The sounds of the Quiz (pure maths). */
object QuizSynth {
    val right by lazy { Synth.then(Synth.tone(659.0, 160, 70.0), Synth.tone(988.0, 300, 130.0, 0.4), 110) }
    val wrong by lazy { Synth.then(Synth.tone(247.0, 220, 100.0), Synth.tone(165.0, 380, 160.0), 150) }
    val done by lazy { Synth.then(Synth.tone(523.0, 200, 90.0), Synth.tone(784.0, 400, 160.0), 150) }
    fun warm() { right; wrong; done }
}

/** What the OS registry lists for the Quiz. */
object QuizProgram {
    val entry = ProgramEntry("games:quiz", QuizVoice.TITLE) { QuizAndroid() }
}
