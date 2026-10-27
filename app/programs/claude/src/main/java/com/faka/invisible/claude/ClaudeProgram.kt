package com.faka.invisible.claude

import android.os.Handler
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit
import java.io.IOException

/** Where the bridge address and token are saved (the app home screen writes them). */
object ClaudeSettings {
    const val URL_KEY = "claude_url"
    const val TOKEN_KEY = "claude_token"
}

/** Timers on the host's handler, one set per run. */
private class HandlerTimers(private val handler: Handler) : Timers {
    private val pending = HashMap<String, Runnable>()
    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; handler.postDelayed(r, delayMs) }
    override fun clear(key: String) { pending.remove(key)?.let { handler.removeCallbacks(it) } }
}

/**
 * Claude bridge, a tool. It long-polls the laptop bridge on a background thread and speaks what comes: "<project> asks: <what>",
 * then the options one by one (the kit Scanner); a click picks the option just spoken. Notes (done, needs you) are one short line.
 * 1 click = pick the option just spoken, 2 clicks = the one before, 3 clicks = skip this one (answer it on the laptop), hold 3 s = leave.
 * Only the button and the network; no microphone.
 */
class ClaudeAndroid : Program {
    private enum class Phase { NONE, LINE, INTRO, ITEM, STUCK, SENDING, SENT }

    private lateinit var host: ProgramHost
    private val handler get() = host.handler
    private lateinit var timers: Timers
    private lateinit var client: BridgeClient
    private val queue = ClaudeQueue()
    private var current: ClaudeEvent? = null
    private var phase = Phase.NONE
    private var awaitItem = false
    @Volatile private var running = false
    private var clicks = 0
    private var downAt = 0L
    private var stopWaiting: (() -> Unit)? = null
    private var down = false
    private val leave = Runnable { shutdown() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    private val scanner: Scanner<Choice> by lazy {
        Scanner(timers, { c -> when (c) { is Choice.Option -> Phrase.content(c.text, "en"); Choice.Again -> ClaudeVoice.READ_AGAIN } }, object : ScanOut {
            override fun say(index: Int, label: Phrase, spearcon: Boolean) { awaitItem = true; host.speak(label) }
            override fun sayFast(word: Phrase) {}
            override fun tone(t: Tone) {}
            override fun leave() { phase = Phase.STUCK; host.speak(ClaudeVoice.WAITING_YOU) }
        }, ScanCadence(pauseMs = { PAUSE_MS }, loops = { LOOPS }, ceremony = false), scanKey = "claude:scan")
    }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        val url = host.prefs.getString(ClaudeSettings.URL_KEY, "").orEmpty()
        val token = host.prefs.getString(ClaudeSettings.TOKEN_KEY, "").orEmpty()
        if (url.isBlank() || token.isBlank()) { host.speak(ClaudeVoice.NOT_SET); host.ended(); return }
        client = BridgeClient(url, token)
        timers = HandlerTimers(host.handler)
        running = true
        host.pauseMusic()
        host.speak(ClaudeVoice.LISTENING)
        Thread({ pollLoop() }, "claude-poll").apply { isDaemon = true }.start()
    }

    override fun stop() = shutdown()

    /** Background thread: the only place that waits for the network. */
    private fun pollLoop() {
        var since = 0
        var up = true
        while (running) {
            try {
                val r = client.poll(since, POLL_SEC)
                since = r.seq
                if (!up) { up = true; handler.post { if (running && phase == Phase.NONE) host.speak(ClaudeVoice.BACK_UP) } }
                if (r.events.isNotEmpty()) handler.post { if (running) onEvents(r.events) }
            } catch (e: IOException) {
                if (up) { up = false; handler.post { if (running && phase == Phase.NONE) host.speak(ClaudeVoice.DOWN) } }
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun onEvents(events: List<ClaudeEvent>) {
        var fresh = false
        for (e in events) if (queue.add(e)) fresh = true
        if (fresh && phase == Phase.NONE) presentNext()
    }

    private fun presentNext() {
        val e = queue.nextByUrgency()
        if (e == null) { current = null; phase = Phase.NONE; return }
        current = e
        present(e)
    }

    /** Speak [e] from the start: the line, then (for a question) the options. */
    private fun present(e: ClaudeEvent) {
        scanner.stop(); awaitItem = false
        if (e.needsAnswer) {
            VoiceKit.narrator.beep(Tone.CLAUDE)
            scanner.load(Choice.of(e))
            phase = Phase.INTRO
            host.speak(ClaudeVoice.asks(e.project, e.summary))
        } else {
            phase = Phase.LINE
            host.speak(if (e.kind == ClaudeEvent.KIND_DONE) ClaudeVoice.done(e.project, e.summary) else ClaudeVoice.needs(e.project, e.summary))
        }
    }

    override fun speechDone() {
        if (!running) return
        when (phase) {
            Phase.INTRO -> { phase = Phase.ITEM; scanner.speakItem(0) }
            Phase.ITEM -> if (awaitItem) { awaitItem = false; scanner.itemDone() }
            Phase.LINE, Phase.SENT -> timers.set("claude:next", NEXT_MS) { presentNext() }
            else -> {}
        }
    }

    override fun down(time: Long) { downAt = time; down = true; handler.removeCallbacks(resolve); handler.postDelayed(leave, Pacing.LEAVE_MS) }

    override fun up(time: Long) {
        handler.removeCallbacks(leave)
        if (down && time - downAt < Pacing.LEAVE_MS) { clicks++; handler.postDelayed(resolve, Pacing.GAP_MS) }
        down = false
    }

    private fun onClicks(n: Int) {
        val e = current
        if (e == null || phase == Phase.NONE) { host.stopSpeech(); host.speak(ClaudeVoice.NOTHING); return }
        if (phase == Phase.SENDING) return
        if (!e.needsAnswer) { host.stopSpeech(); timers.clear("claude:next"); presentNext(); return }   // a click skips a note
        when {
            n >= 3 -> { scanner.stop(); awaitItem = false; host.stopSpeech(); host.speak(ClaudeVoice.SKIPPED); phase = Phase.SENT }
            phase == Phase.INTRO || phase == Phase.STUCK -> { host.stopSpeech(); present(e) }
            n == 2 -> { host.stopSpeech(); awaitItem = false; phase = Phase.ITEM; scanner.prevIn(0) }
            else -> when (val c = scanner.list.current) {
                is Choice.Option -> send(e, c.text)
                Choice.Again -> { host.stopSpeech(); present(e) }
            }
        }
    }

    private fun send(e: ClaudeEvent, choice: String) {
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        stopWaiting = host.waiting()
        Thread({
            val code = try { client.answer(e.id, choice) } catch (_: IOException) { -1 }
            handler.post {
                stopWaiting?.invoke(); stopWaiting = null
                if (!running) return@post
                when (code) {
                    200 -> { phase = Phase.SENT; host.speak(ClaudeVoice.sent(choice)) }
                    409 -> { phase = Phase.SENT; host.speak(ClaudeVoice.TOO_LATE) }
                    else -> { host.speak(ClaudeVoice.FAILED); timers.set("claude:retry", NEXT_MS) { present(e) } }
                }
            }
        }, "claude-answer").start()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        handler.removeCallbacks(leave); handler.removeCallbacks(resolve)
        timers.clear("claude:next"); timers.clear("claude:retry"); scanner.stop()
        stopWaiting?.invoke(); stopWaiting = null
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    private companion object { const val POLL_SEC = 25; const val RETRY_MS = 5000L; const val PAUSE_MS = 1200L; const val LOOPS = 2; const val NEXT_MS = 700L }
}

/** What the OS registry lists for the Claude bridge (a tool of the home hub). */
object ClaudeProgram {
    val entry = ProgramEntry("hub:claude", ClaudeVoice.TITLE) { ClaudeAndroid() }
}
