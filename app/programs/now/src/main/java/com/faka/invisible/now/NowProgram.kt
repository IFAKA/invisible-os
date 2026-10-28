package com.faka.invisible.now

import android.Manifest
import android.content.pm.PackageManager
import android.os.BatteryManager
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Voice
import java.util.Calendar

/** What Now needs from the OS: the two lookups that already exist there. [done] runs on the main thread. */
interface NowServices {
    fun weather(done: (Phrase) -> Unit)
    fun nextEvent(done: (Phrase) -> Unit)
}

/**
 * Now, a tool: one page of facts, one at a time: the time, the battery, the next calendar event (only with the Calendar
 * permission) and the weather (only with a location permission). Click: next fact. Two clicks: say it again. Three clicks: the
 * fact before (from the first one: leave). After the last fact a click leaves. Hold 3 s: leave. No microphone.
 */
class NowProgram(private val services: NowServices) : Program {
    private lateinit var host: ProgramHost
    private lateinit var facts: List<NowCore.Fact>
    @Volatile private var running = false
    private var index = 0
    private var ticket = 0   // a late answer from the lookups is dropped when the person has moved on
    private var clicks = 0
    private var downAt = 0L
    private val leave = Runnable { finish() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host; running = true
        val ctx = host.context
        facts = NowCore.facts(
            calendar = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
            location = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        Trace.ev("program", "now start facts=${facts.joinToString { it.name.lowercase() }}")
        index = 0
        say(0)
    }

    private fun show(p: Phrase) { host.screen(listOf(p.text), 0); host.speak(p) }

    /** Says fact [i]. The lookups answer later; the ticket drops an answer that came after the person moved on. */
    private fun say(i: Int) {
        index = i
        val mine = ++ticket
        host.stopSpeech()
        val fact = facts[i]
        Trace.ev("program", "now fact ${fact.name.lowercase()}")
        val answer: (Phrase) -> Unit = { p -> if (running && mine == ticket) show(p) }
        when (fact) {
            NowCore.Fact.TIME -> Calendar.getInstance().let { show(Voice.time(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))) }
            NowCore.Fact.BATTERY -> {
                val b = host.context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                show(Voice.battery(b))
            }
            NowCore.Fact.EVENT -> services.nextEvent(answer)
            NowCore.Fact.WEATHER -> services.weather(answer)
        }
    }

    private fun onClicks(n: Int) {
        when {
            n >= 3 -> { ticket++; val b = NowCore.back(index); if (b == null) finish() else say(b) }
            n == 2 -> say(index)
            else -> { ticket++; val x = NowCore.next(index, facts.size); if (x == null) { host.stopSpeech(); show(NowVoice.DONE); host.later(leave, 1200L) } else say(x) }
        }
    }

    override fun down(time: Long) { downAt = time; host.cancel(resolve); host.later(leave, Pacing.LEAVE_MS) }

    override fun up(time: Long) {
        host.cancel(leave)
        if (time - downAt < Pacing.LEAVE_MS) { clicks++; host.later(resolve, Pacing.GAP_MS) }
    }

    override fun stop() = finish()

    private fun finish() {
        if (!running) return
        running = false; ticket++
        host.cancel(leave); host.cancel(resolve)
        host.stopSpeech()
        host.ended()
    }
}

object NowEntry {
    fun entry(services: NowServices) = ProgramEntry("hub:now", NowVoice.TITLE) { NowProgram(services) }
}
