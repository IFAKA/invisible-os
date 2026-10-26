package com.faka.invisible.guard

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.faka.invisible.echo.EchoMixer
import com.faka.invisible.voicekit.GuardVoice
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.RawButton
import com.faka.invisible.voicekit.GameAudio
import com.faka.invisible.voicekit.Timers

/** What GUARD needs from the app (speech, the user's music, the log, which sound output is in use). */
interface GuardHost {
    /** Starts the OS wait sound (a soft heartbeat while the sounds are built); call the result when ready. */
    fun waiting(): () -> Unit
    fun speak(text: Phrase)
    fun stopSpeech()
    fun pauseMusic()
    fun resumeMusic()
    fun log(line: String)
    /** A short key for the current sound output (earbuds, wired, phone speaker); the click delay is saved per output. */
    fun outputDevice(): String
}

/** Android side of GUARD: the low-latency audio engine, the mixer lock, the timers, the saved best score and click delay. No audio input of any kind. */
class GuardAndroid(
    private val prefs: SharedPreferences,
    private val handler: Handler,
    private val host: GuardHost,
    private val onEnded: () -> Unit,
) : RawButton {
    private val sr = GuardSynth.SR
    private val block = sr * 5 / 1000
    private val lock = Any()
    private val mixer = EchoMixer()
    private val keyed = HashMap<String, Runnable>()
    @Volatile private var running = false
    @Volatile private var latency = 0.0
    private val audio = GameAudio(sr, block, { buf, n -> synchronized(lock) { mixer.render(buf, n) } }, { running },
        { ms -> latency = if (latency == 0.0) ms else latency * 0.8 + ms * 0.2 })
    private var game: GuardGame? = null
    private var gen = 0
    private var device = "speaker"

    override val active get() = running

    private val timers = object : Timers {
        override fun set(key: String, delayMs: Long, task: () -> Unit) {
            clear(key)
            val r = Runnable { keyed.remove(key); task() }
            keyed[key] = r; handler.postDelayed(r, delayMs)
        }
        override fun clear(key: String) { keyed.remove(key)?.let { handler.removeCallbacks(it) } }
    }

    private fun biasKey() = "guard_bias_$device"

    private val out = object : GuardOut {
        override fun nowFrame() = synchronized(lock) { mixer.nowFrame() }
        override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) = synchronized(lock) { mixer.play(sound, atFrame, pan, gain) }
        override fun clearScheduled() = synchronized(lock) { mixer.clear() }
        override fun speak(text: Phrase) = host.speak(text)
        override fun stopSpeech() = host.stopSpeech()
        override fun best() = prefs.getInt("guard_best", 0)
        override fun saveBest(score: Int) { prefs.edit().putInt("guard_best", score).apply() }
        override fun bias(): Double? = if (prefs.contains(biasKey())) prefs.getFloat(biasKey(), 0f).toDouble() else null
        override fun saveBias(ms: Double) { prefs.edit().putFloat(biasKey(), ms.toFloat()).putFloat("guard_bias", ms.toFloat()).apply() }
        override fun taught() = prefs.getBoolean("guard_taught", false)
        override fun saveTaught(v: Boolean) { prefs.edit().putBoolean("guard_taught", v).apply() }
        override fun log(line: String) = host.log(line)
        override fun leave() { val g = gen; handler.postDelayed({ if (g == gen) shutdown() }, 400) }
    }

    /** Main thread. Builds the sounds in the background (the phone is slow at it), then opens the audio and starts. */
    fun start() {
        if (running) return
        running = true
        gen++
        val g = gen
        latency = 0.0
        device = try { host.outputDevice() } catch (e: Throwable) { "speaker" }
        host.pauseMusic()
        val w = host.waiting()
        Thread({
            val t0 = SystemClock.uptimeMillis()
            try { GuardSynth.warm() } catch (e: Throwable) { Log.e("GUARD", "synth failed", e) }
            Log.d("GUARD", "sounds ready in ${SystemClock.uptimeMillis() - t0} ms")
            handler.post { w(); if (g == gen && running) begin() }
        }, "guard-load").start()
    }

    private fun begin() {
        if (!audio.open()) { host.speak(GuardVoice.LOADING); shutdown(); return }
        audio.start()
        game = GuardGame(out, timers, { SystemClock.uptimeMillis() }, { latency.toLong() }).also { it.start() }
    }

    override fun down(time: Long) { game?.down(time) }
    override fun up(time: Long) { game?.up(time) }
    fun speechDone() { game?.onSpeechDone() }

    fun stop() {
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
        onEnded()
    }
}
