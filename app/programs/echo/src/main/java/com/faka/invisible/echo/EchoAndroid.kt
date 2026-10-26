package com.faka.invisible.echo

import android.os.SystemClock
import android.util.Log
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.RawButton
import com.faka.invisible.voicekit.FrameMixer
import com.faka.invisible.voicekit.GameAudio
import com.faka.invisible.voicekit.Timers

/** Android side of ECHO: the low-latency audio engine, the mixer lock, the timers, the saved best score. No audio input of any kind. */
class EchoAndroid : Program {
    private lateinit var host: ProgramHost
    private val handler get() = host.handler
    private val prefs get() = host.prefs
    private val sr = EchoSynth.SR
    private val block = sr * 5 / 1000
    private val lock = Any()
    private val mixer = FrameMixer()
    private val keyed = HashMap<String, Runnable>()
    @Volatile private var running = false
    @Volatile private var latency = 0.0
    private val audio = GameAudio(sr, block, { buf, n -> synchronized(lock) { mixer.render(buf, n) } }, { running },
        { ms -> latency = if (latency == 0.0) ms else latency * 0.8 + ms * 0.2 })
    private var game: EchoGame? = null
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

    private val out = object : EchoOut {
        override fun nowFrame() = synchronized(lock) { mixer.nowFrame() }
        override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) = synchronized(lock) { mixer.play(sound, atFrame, pan, gain) }
        override fun speak(text: Phrase) = host.speak(text)
        override fun stopSpeech() = host.stopSpeech()
        override fun best() = prefs.getInt("echo_best", 0)
        override fun saveBest(score: Int) { prefs.edit().putInt("echo_best", score).apply() }
        override fun log(line: String) = host.log(line)
        override fun leave() { val g = gen; handler.postDelayed({ if (g == gen) shutdown() }, 400) }
    }

    /** Main thread. Builds the sounds in the background (the phone is slow at it), then opens the audio and starts. */
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
            val t0 = SystemClock.uptimeMillis()
            try { EchoSynth.warm() } catch (e: Throwable) { Log.e("ECHO", "synth failed", e) }
            Log.d("ECHO", "sounds ready in ${SystemClock.uptimeMillis() - t0} ms")
            handler.post { w(); if (g == gen && running) begin() }
        }, "echo-load").start()
    }

    private fun begin() {
        if (!audio.open()) { host.speak(EchoVoice.LOADING); shutdown(); return }
        audio.start()
        game = EchoGame(out, timers, { SystemClock.uptimeMillis() }, { latency.toLong() }).also { it.start() }
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

/** What the OS registry lists for Echo. */
object EchoProgram {
    val entry = ProgramEntry("games:echo", EchoVoice.TITLE) { EchoAndroid() }
}
