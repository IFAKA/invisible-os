package com.faka.invisible.counter

import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Pacing

/**
 * Counter. The OS gives a program the raw button: only [down] and [up] with the time. Counting clicks is the program's job.
 *  - one click: add one and say the total
 *  - two clicks: start again from zero
 *  - hold 3 seconds: leave (every program must have this)
 * No microphone. All speech goes through [ProgramHost.speak] with a Phrase from CounterSentences.kt.
 */
class CounterProgram : Program {
    private lateinit var host: ProgramHost
    private val core = CounterCore()
    private var running = false
    private var clicks = 0
    private var downAt = 0L
    private val leave = Runnable { shutdown() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    /** True while the program owns the button. */
    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        running = true
        // Say how to leave in the first minute: the player must never feel trapped.
        host.speak(CounterVoice.WELCOME)
        host.speak(CounterVoice.HOW)
        host.speak(CounterVoice.LEAVE)
    }

    /** The OS stops the program (new program, or the exit gesture). End with ended(). */
    override fun stop() = shutdown()

    override fun down(time: Long) {
        downAt = time
        host.cancel(resolve)
        host.later(leave, Pacing.LEAVE_MS)   // still held after 3 s: leave
    }

    override fun up(time: Long) {
        host.cancel(leave)
        if (time - downAt < Pacing.LEAVE_MS) {              // a click: count it, decide when the burst is over
            clicks++
            host.later(resolve, Pacing.GAP_MS)
        }
    }

    private fun onClicks(n: Int) {
        host.stopSpeech()                                    // a new answer cuts the old one
        val total = core.clicked(n)
        host.speak(if (total == 0) CounterVoice.ZERO else CounterVoice.count(total))
    }

    private fun shutdown() {
        if (!running) return
        running = false
        host.cancel(leave); host.cancel(resolve)
        host.stopSpeech()
        host.ended()                                         // exactly once: the OS plays its close sound
    }
}

/** What the OS registry lists for this program. Games list entries use ids `games:<name>`; tools use `hub:<name>`. */
object CounterEntries {
    val entry = ProgramEntry("games:counter", CounterVoice.TITLE) { CounterProgram() }
}
