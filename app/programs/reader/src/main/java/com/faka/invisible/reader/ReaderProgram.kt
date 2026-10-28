package com.faka.invisible.reader

import android.content.ClipboardManager
import android.os.Handler
import com.faka.invisible.logickit.Paragraphs
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit

private class HandlerTimers(private val handler: Handler) : Timers {
    private val pending = HashMap<String, Runnable>()
    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; handler.postDelayed(r, delayMs) }
    override fun clear(key: String) { pending.remove(key)?.let { handler.removeCallbacks(it) } }
}

/**
 * Reader, a tool. Reads a shared (or copied) text aloud, one paragraph after the other, in the text's own language.
 * Button: 1 click = next paragraph, 2 clicks = this paragraph again, 3 clicks = leave, hold = fast scroll through the
 * paragraphs (release reads that one), hold 3 s = leave. It remembers where it stopped for each text. No microphone.
 */
class ReaderProgram : Program {
    private enum class Phase { NONE, INTRO, ITEM, ENDING }

    private lateinit var host: ProgramHost
    private lateinit var timers: Timers
    private lateinit var scanner: Scanner<String>
    private var phase = Phase.NONE
    private var text = ""
    private var lang: String? = null
    @Volatile private var running = false
    private var awaitItem = false
    private var clicks = 0
    private var down = false
    private var downAt = 0L
    private var fastOn = false
    private val leave = Runnable { shutdown() }
    private val holdFast = Runnable { startFast() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        val clip = try { host.context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() } catch (e: Exception) { null }
        val t = ReaderStore.pick(host.prefs, clip)
        val parts = t?.let { Paragraphs.split(it) }.orEmpty()
        if (t == null || parts.isEmpty()) { Trace.ev("program", "reader nothing"); host.speak(ReaderVoice.NOTHING); host.ended(); return }
        running = true
        text = t; lang = ReaderStore.lang(t)
        timers = HandlerTimers(host.handler)
        host.pauseMusic()
        scanner = Scanner(timers, { Phrase.content(it, lang) }, object : ScanOut {
            override fun say(index: Int, label: Phrase, spearcon: Boolean) {
                awaitItem = true; phase = Phase.ITEM
                ReaderStore.save(host.prefs, text, index)
                Trace.ev("program", "reader part ${index + 1}/${scanner.list.items.size}")
                host.screen(scanner.list.items, index)
                host.speak(label)
            }
            override fun sayFast(word: Phrase) { host.speak(word) }
            override fun tone(t: Tone) { VoiceKit.narrator.beep(t) }
            override fun leave() { end() }
        }, ScanCadence(pauseMs = { PAUSE_MS }, loops = { 1 }, ceremony = false), scanKey = "reader:scan")
        scanner.load(parts)
        val at = ReaderStore.position(host.prefs, t, parts.size)
        Trace.ev("program", "reader start parts=${parts.size} at=${at + 1} lang=${lang ?: "en"}")
        scanner.list.moveTo(at)
        phase = Phase.INTRO
        host.screen(parts, at)
        host.speak(if (at > 0) ReaderVoice.resume(at + 1, parts.size) else ReaderVoice.start(parts.size))
    }

    /** The last paragraph is read: say so, forget the position, leave. */
    private fun end() {
        ReaderStore.finished(host.prefs, text)
        phase = Phase.ENDING; awaitItem = false
        Trace.ev("program", "reader end")
        host.speak(ReaderVoice.END)
    }

    override fun speechDone() {
        if (!running) return
        when (phase) {
            Phase.INTRO -> scanner.speakItem(scanner.list.index)
            Phase.ITEM -> if (awaitItem) { awaitItem = false; scanner.itemDone() }
            Phase.ENDING -> shutdown()
            else -> {}
        }
    }

    private fun onClicks(n: Int) {
        when {
            n >= 3 -> shutdown()
            phase == Phase.ENDING -> shutdown()
            phase == Phase.INTRO -> { host.stopSpeech(); phase = Phase.ITEM; scanner.speakItem(scanner.list.index) }
            n == 2 -> { host.stopSpeech(); awaitItem = false; scanner.resumeIn(0) }
            scanner.list.isLast -> { scanner.stop(); host.stopSpeech(); awaitItem = false; end() }
            else -> { host.stopSpeech(); awaitItem = false; scanner.nextIn(0) }
        }
    }

    private fun startFast() {
        if (!running || phase != Phase.ITEM) return
        clicks = 0; host.handler.removeCallbacks(resolve)
        host.stopSpeech(); awaitItem = false; scanner.stop()
        fastOn = true
        scanner.startFast()
    }

    override fun down(time: Long) {
        downAt = time; down = true; host.handler.removeCallbacks(resolve)
        host.handler.postDelayed(holdFast, Pacing.HOLD_MS); host.handler.postDelayed(leave, Pacing.LEAVE_MS)
    }

    override fun up(time: Long) {
        host.handler.removeCallbacks(leave); host.handler.removeCallbacks(holdFast)
        if (fastOn) {
            fastOn = false; down = false
            if (scanner.endFast()) { host.stopSpeech(); awaitItem = false; phase = Phase.ITEM; scanner.resumeIn(0) }
            return
        }
        if (down && time - downAt < Pacing.HOLD_MS) { clicks++; host.handler.postDelayed(resolve, Pacing.GAP_MS) }
        down = false
    }

    override fun stop() = shutdown()

    private fun shutdown() {
        if (!running) return
        running = false
        host.handler.removeCallbacks(leave); host.handler.removeCallbacks(resolve); host.handler.removeCallbacks(holdFast)
        scanner.stop()
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    private companion object { const val PAUSE_MS = 500L }
}

object ReaderEntry {
    val entry = ProgramEntry("hub:reader", ReaderVoice.TITLE) { ReaderProgram() }
}
