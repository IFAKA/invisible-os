package com.faka.invisible.clicktest

import android.os.SystemClock
import android.util.Log
import com.faka.invisible.logickit.ClickProfile
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.FrameMixer
import com.faka.invisible.voicekit.GameAudio
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers

/** Android side of the Click test: the low-latency audio stream (the audio clock), the timers, the saved profile. No audio input of any kind. */
class ClickTestAndroid : Program {
    private lateinit var host: ProgramHost
    private val handler get() = host.handler
    private val sr = ClickTestSynth.SR
    private val block = sr * 5 / 1000
    private val lock = Any()
    private val mixer = FrameMixer()
    private val keyed = HashMap<String, Runnable>()
    @Volatile private var running = false
    @Volatile private var latency = 0.0
    private val audio = GameAudio(sr, block, { buf, n -> synchronized(lock) { mixer.render(buf, n) } }, { running },
        { ms -> latency = if (latency == 0.0) ms else latency * 0.8 + ms * 0.2 })
    private var game: ClickTestGame? = null
    private var gen = 0

    override val active get() = running

    private val timers = object : Timers {
        override fun set(key: String, delayMs: Long, task: () -> Unit) {
            clear(key)
            val r = Runnable { keyed.remove(key); task() }
            keyed[key] = r; handler.postDelayed(r, delayMs)
        }
        override fun clear(key: String) { keyed.remove(key)?.let { handler.removeCallbacks(it) } }
    }

    private val out = object : ClickTestOut {
        override fun nowFrame() = synchronized(lock) { mixer.nowFrame() }
        override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) = synchronized(lock) { mixer.play(sound, atFrame, pan, gain) }
        override fun speak(text: Phrase) = host.speak(text)
        override fun stopSpeech() = host.stopSpeech()
        override fun log(line: String) { host.log(line); Trace.ev("program", line) }
        override fun save(json: String) { host.prefs.edit().putString(ClickProfile.KEY, json).apply(); Trace.ev("program", "clicktest saved $json") }
        override fun leave() { val g = gen; handler.postDelayed({ if (g == gen) shutdown() }, 400) }
    }

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        running = true
        gen++
        val g = gen
        latency = 0.0
        host.pauseMusic()
        val w = host.waiting()
        Thread({
            try { ClickTestSynth.warm() } catch (e: Throwable) { Log.e("CLICKTEST", "synth failed", e) }
            handler.post { w(); if (g == gen && running) begin() }
        }, "clicktest-load").start()
    }

    private fun begin() {
        if (!audio.open()) { shutdown(); return }
        audio.start()
        game = ClickTestGame(out, timers, { SystemClock.uptimeMillis() }, { latency.toLong() }).also { it.start() }
    }

    override fun down(time: Long) { game?.down(time) }
    override fun up(time: Long) { game?.up(time) }
    override fun speechDone() { game?.onSpeechDone() }

    override fun stop() {
        if (!running) return
        game?.stop()
        shutdown()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        for (k in keyed.keys.toList()) timers.clear(k)
        game = null
        audio.join()
        host.resumeMusic()
        host.ended()
    }
}

/** What the OS registry lists for the Click test (a tool of the home hub). */
object ClickTestProgram {
    val entry = ProgramEntry("hub:clicktest", ClickTestVoice.TITLE) { ClickTestAndroid() }
}
