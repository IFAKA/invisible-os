package com.faka.invisible.tell

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.RawButton
import com.faka.invisible.voicekit.TellAudio
import com.faka.invisible.voicekit.TellVoice
import com.faka.invisible.voicekit.Timers

/** What the duel needs from the app (speech, the user's music, the log). */
interface TellHost {
    /** Starts the OS wait sound (a soft heartbeat while the sounds are built); call the result when ready. */
    fun waiting(): () -> Unit
    fun speak(text: Phrase, slow: Boolean = false)
    fun stopSpeech()
    fun pauseMusic()
    fun resumeMusic()
    fun log(line: String)
}

/**
 * Android side of TELL: owns the mixer, a TellAudio engine (low-latency stereo, 44.1 kHz, float, 5 ms blocks),
 * the latency estimate, the solver thread and the saved rung. Game calls come on the main thread ([handler]); the mixer is
 * guarded by one lock shared with the audio thread. No audio input of any kind.
 */
class TellAndroid(
    private val prefs: SharedPreferences,
    private val handler: Handler,
    private val host: TellHost,
    private val onEnded: () -> Unit,
    private val coach: Boolean = false,
) : RawButton {
    private val sr = 44100
    private val block = sr * 5 / 1000   // 220 frames
    private val lock = Any()
    private lateinit var mixer: TellMixer   // built on a background thread: it takes seconds on a phone
    private val solver = TellSolver()
    private val keyed = HashMap<String, Runnable>()
    @Volatile private var running = false
    @Volatile private var latency = 0.0
    private val audio = TellAudio(sr, block, { buf, n -> synchronized(lock) { mixer.render(buf, n) } }, { running },
        { ms -> latency = if (latency == 0.0) ms else latency * 0.8 + ms * 0.2 })
    private var solverThread: Thread? = null
    private var game: TellGame? = null
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

    private val out = object : TellOut {
        override fun play(cue: Cue, side: Side) = synchronized(lock) { mixer.play(cue, side) }
        override fun status(myEnergy: Int, oppEnergy: Int) = synchronized(lock) { mixer.status(myEnergy, oppEnergy) }
        override fun lifeNotes(side: Side, life: Int) = synchronized(lock) { mixer.lifeNotes(side, life) }
        override fun setHold(level: Int) = synchronized(lock) { mixer.setHold(level) }
        override fun startBed() = synchronized(lock) { mixer.startBed() }
        override fun setTension(level: Int) = synchronized(lock) { mixer.setTension(level) }
        override fun stopBed(tapeStop: Boolean) = synchronized(lock) { mixer.stopBed(tapeStop) }
        override fun speak(text: Phrase) = host.speak(text)
        override fun coachSpeak(text: Phrase) = host.speak(text, true)
        override fun stopSpeech() = host.stopSpeech()
        override fun pauseMusic() = host.pauseMusic()
        override fun resumeMusic() = host.resumeMusic()
        override fun log(line: String) = host.log(line)
        override fun savedRung() = prefs.getInt("tellRung", 1)
        override fun saveRung(rung: Int) { prefs.edit().putInt("tellRung", rung).apply() }
        override fun level() = prefs.getInt("tell_level", if (prefs.getBoolean("tell_taught", false)) 4 else 1)
        override fun saveLevel(level: Int) { prefs.edit().putInt("tell_level", level).putBoolean("tell_taught", level >= 4).apply() }
        override fun leave() { val g = gen; handler.postDelayed({ if (g == gen) shutdown() }, 900) }   // lets the tape stop and the tails finish
    }

    /** Main thread. Loads the sounds in the background, then starts the audio, the solver and the first match. */
    fun start() {
        if (running) return
        running = true
        gen++
        val g = gen
        latency = 0.0
        if (!TellSynth.warmed) host.speak(TellVoice.LOADING)
        val w = host.waiting()
        Thread({
            val t0 = SystemClock.uptimeMillis()
            val m = try { TellMixer(sr) } catch (e: Throwable) { Log.e("TELL", "mixer failed", e); null }
            Log.d("TELL", "sounds ready in ${SystemClock.uptimeMillis() - t0} ms")
            handler.post { w(); if (g == gen && running) begin(m) }
        }, "tell-load").start()
    }

    private fun begin(m: TellMixer?) {
        if (m == null || !audio.open()) { host.speak(TellVoice.COULD_NOT_START); running = false; onEnded(); return }
        mixer = m
        audio.start()
        solverThread = Thread({ try { solver.solve() } catch (e: Throwable) { Log.e("TELL", "solve failed", e) } }, "tell-solver")
            .also { it.priority = Thread.MIN_PRIORITY; it.start() }
        game = TellGame(solver, out, timers, { SystemClock.uptimeMillis() }, { latency.toLong() },
            solved = { solver.isSolved }, forceCoach = coach).also { it.start() }
    }

    override fun down(time: Long) { game?.down(time) }
    override fun up(time: Long) { game?.up(time) }
    /** The speech engine finished the last sentence. */
    fun speechDone() { game?.onSpeechDone() }

    /** Main thread. The app stops the duel from outside (also safe to call twice). */
    fun stop() {
        if (!running) return
        game?.stop()          // calls out.leave(), which shuts down after the tail
        shutdown()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        for (k in keyed.keys.toList()) timers.clear(k)
        game = null
        audio.join()
        onEnded()
    }
}
