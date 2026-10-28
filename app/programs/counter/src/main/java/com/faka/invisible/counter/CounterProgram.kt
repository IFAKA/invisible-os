package com.faka.invisible.counter

import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.GestureRules
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit

/** Pure rules of the counters. */
object CounterCore {
    val NAMES = listOf("Reps", "Laps", "Spent")
    /** The number is spoken on every fifth count; the others only tick. */
    const val SAY_EVERY = 5
    fun spoken(n: Int) = n > 0 && n % SAY_EVERY == 0
    fun add(n: Int) = n + 1
    fun undo(n: Int) = maxOf(0, n - 1)
    fun next(i: Int) = (i + 1) % NAMES.size
}

/**
 * Counter, a tally. One click adds 1: a tick, and the number every fifth count. Two clicks undo. A hold of 1 s reads the total.
 * Four clicks switch to the next counter (Reps, Laps, Spent); each remembers its number in the prefs. Triple click leaves,
 * and so does a hold of 3 s. No microphone.
 */
class CounterProgram(private val beep: (Tone) -> Unit = { VoiceKit.narrator.beep(it) }) : Program {
    private lateinit var host: ProgramHost
    private var running = false
    private var which = 0
    private var presses = ArrayList<Pair<Long, Long>>()
    private var downAt = 0L
    private var read = false   // this press already read the total
    private val leave = Runnable { finish() }
    private val readTotal = Runnable { read = true; sayTotal() }
    private val resolve = Runnable { val g = GestureRules.classify(presses); presses = ArrayList(); if (running) onGesture(g) }

    override val active get() = running
    private val name get() = CounterCore.NAMES[which]
    private fun key(i: Int) = "counter_${CounterCore.NAMES[i]}"
    private var count: Int
        get() = host.prefs.getInt(key(which), 0)
        set(v) { host.prefs.edit().putInt(key(which), v).apply() }

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host; running = true
        which = CounterCore.NAMES.indexOf(host.prefs.getString(LAST_KEY, null)).coerceAtLeast(0)
        Trace.ev("program", "counter start $name $count")
        host.pauseMusic()
        host.speak(CounterVoice.total(name, count))
        host.speak(CounterVoice.HELP)
        host.screen(CounterCore.NAMES.indices.map { "${CounterCore.NAMES[it]}, ${host.prefs.getInt(key(it), 0)}" }, which)
    }

    override fun stop() = finish()

    override fun down(time: Long) {
        downAt = time; read = false
        host.cancel(resolve); host.stopSpeech()
        host.later(readTotal, HOLD_READ_MS); host.later(leave, Pacing.LEAVE_MS)
    }

    override fun up(time: Long) {
        host.cancel(leave); host.cancel(readTotal)
        if (read || time - downAt >= Pacing.LEAVE_MS) return
        if (time - downAt >= HOLD_READ_MS) { sayTotal(); return }
        presses.add(downAt to time)
        host.later(resolve, Pacing.GAP_MS)
    }

    private fun onGesture(g: Gesture?) {
        when (g) {
            Gesture.SINGLE -> {
                val n = CounterCore.add(count); count = n
                Trace.ev("program", "counter $name $n")
                if (CounterCore.spoken(n)) host.speak(CounterVoice.number(n)) else beep(Tone.TICK)
            }
            Gesture.DOUBLE -> { val n = CounterCore.undo(count); count = n; Trace.ev("program", "counter $name undo $n"); beep(Tone.UNDO); host.speak(CounterVoice.number(n)) }
            Gesture.TRIPLE -> finish()
            Gesture.QUAD -> {
                which = CounterCore.next(which)
                host.prefs.edit().putString(LAST_KEY, name).apply()
                Trace.ev("program", "counter switch $name")
                sayTotal()
            }
            else -> {}
        }
    }

    private fun sayTotal() { host.stopSpeech(); host.speak(CounterVoice.total(name, count)) }

    private fun finish() {
        if (!running) return
        running = false
        host.cancel(leave); host.cancel(resolve); host.cancel(readTotal)
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    companion object {
        const val LAST_KEY = "counter_last"
        const val HOLD_READ_MS = 1000L
    }
}

object CounterEntry {
    val entry = ProgramEntry("hub:counter", CounterVoice.TITLE) { CounterProgram() }
}
