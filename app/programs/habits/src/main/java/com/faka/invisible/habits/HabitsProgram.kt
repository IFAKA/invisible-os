package com.faka.invisible.habits

import com.faka.invisible.logickit.Habits
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.GestureRules
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit
import java.time.LocalDate

/**
 * Habits, a daily check-in. Each habit is read ("Water, streak 3"): one click = yes, two clicks = no, then the next one.
 * Triple click or a hold of 3 s leaves. The days done are saved per habit in the prefs ([Habits]); the list of habits is the prefs
 * key [LIST_KEY] (one name per line, set from the app screen) or the starter list. No microphone.
 */
class HabitsProgram(
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
    private val beep: (Tone) -> Unit = { VoiceKit.narrator.beep(it) },
) : Program {
    private lateinit var host: ProgramHost
    private var running = false
    private var names = Habits.STARTER
    private var at = 0
    private var presses = ArrayList<Pair<Long, Long>>()
    private var downAt = 0L
    private val leave = Runnable { finish() }
    private val resolve = Runnable { val g = GestureRules.classify(presses); presses = ArrayList(); if (running) onGesture(g) }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host; running = true
        names = Habits.names(host.prefs.getString(LIST_KEY, null)); at = 0
        Trace.ev("program", "habits start ${names.size}")
        host.pauseMusic()
        host.speak(HabitsVoice.INTRO)
        read()
    }

    override fun stop() = finish()

    override fun down(time: Long) { downAt = time; host.cancel(resolve); host.stopSpeech(); host.later(leave, Pacing.LEAVE_MS) }

    override fun up(time: Long) {
        host.cancel(leave)
        if (time - downAt >= Pacing.LEAVE_MS) return
        presses.add(downAt to time)
        host.later(resolve, Pacing.GAP_MS)
    }

    private fun onGesture(g: Gesture?) {
        when (g) {
            Gesture.SINGLE -> answer(true)
            Gesture.DOUBLE -> answer(false)
            Gesture.TRIPLE -> finish()
            else -> read()   // anything else: hear the habit again
        }
    }

    private fun key(i: Int) = "habit_days_${names[i]}"

    private fun read() {
        if (at >= names.size) return
        val streak = Habits.streak(Habits.decode(host.prefs.getString(key(at), null)), today())
        host.speak(HabitsVoice.habit(names[at], streak))
        host.screen(names.mapIndexed { i, n -> if (i < at) "$n, answered" else n }, at)
    }

    private fun answer(yes: Boolean) {
        if (at >= names.size) return
        val k = key(at)
        val days = Habits.mark(Habits.decode(host.prefs.getString(k, null)), today(), yes)
        host.prefs.edit().putString(k, Habits.encode(days)).apply()
        Trace.ev("program", "habits ${names[at]} ${if (yes) "yes" else "no"} streak ${Habits.streak(days, today())}")
        beep(if (yes) Tone.PICK else Tone.CANCEL)
        host.speak(if (yes) HabitsVoice.YES else HabitsVoice.NO)
        at++
        if (at >= names.size) { host.speak(HabitsVoice.DONE); host.later(leave, END_MS) } else read()
    }

    private fun finish() {
        if (!running) return
        running = false
        host.cancel(leave); host.cancel(resolve)
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    companion object {
        /** Prefs key: the habit names, one per line. Empty or missing = the starter list. */
        const val LIST_KEY = "habits_list"
        private const val END_MS = 1500L
    }
}

object HabitsEntry {
    val entry = ProgramEntry("hub:habits", HabitsVoice.TITLE) { HabitsProgram() }
}
