package com.faka.invisible.tell

import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.faka.invisible.Timers

/** What the duel needs from the app (speech, the user's music, the log). */
interface TellHost {
    fun speak(text: String)
    fun stopSpeech()
    fun pauseMusic()
    fun resumeMusic()
    fun log(line: String)
}

/**
 * Android side of TELL: owns the mixer, a thread that feeds a low-latency stereo AudioTrack (44.1 kHz, float, 5 ms blocks),
 * the latency estimate, the solver thread and the saved rung. Game calls come on the main thread ([handler]); the mixer is
 * guarded by one lock shared with the audio thread. No audio input of any kind.
 */
class TellAndroid(
    private val prefs: SharedPreferences,
    private val handler: Handler,
    private val host: TellHost,
    private val onEnded: () -> Unit,
) {
    private val sr = 44100
    private val block = sr * 5 / 1000   // 220 frames
    private val lock = Any()
    private val mixer = TellMixer(sr)
    private val solver = TellSolver()
    private val keyed = HashMap<String, Runnable>()
    @Volatile private var running = false
    @Volatile private var latency = 0.0
    private var thread: Thread? = null
    private var solverThread: Thread? = null
    private var game: TellGame? = null
    private var gen = 0

    val active get() = running

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
        override fun speak(text: String) = host.speak(text)
        override fun stopSpeech() = host.stopSpeech()
        override fun pauseMusic() = host.pauseMusic()
        override fun resumeMusic() = host.resumeMusic()
        override fun log(line: String) = host.log(line)
        override fun savedRung() = prefs.getInt("tellRung", 1)
        override fun saveRung(rung: Int) { prefs.edit().putInt("tellRung", rung).apply() }
        override fun leave() { val g = gen; handler.postDelayed({ if (g == gen) shutdown() }, 900) }   // lets the tape stop and the tails finish
    }

    /** Main thread. Starts the audio, the solver and the first match. */
    fun start() {
        if (running) return
        val track = try { buildTrack() } catch (e: Exception) { Log.e("TELL", "AudioTrack failed", e); null }
        if (track == null) { host.speak("Duel could not start"); onEnded(); return }
        running = true
        gen++
        latency = 0.0
        thread = Thread({ pump(track) }, "tell-audio").also { it.start() }
        solverThread = Thread({ try { solver.solve() } catch (e: Throwable) { Log.e("TELL", "solve failed", e) } }, "tell-solver")
            .also { it.priority = Thread.MIN_PRIORITY; it.start() }
        game = TellGame(solver, out, timers, { SystemClock.uptimeMillis() }, { latency.toLong() },
            solved = { solver.isSolved }).also { it.start() }
    }

    fun down(timeMs: Long) { game?.down(timeMs) }
    fun up(timeMs: Long) { game?.up(timeMs) }

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
        thread?.let { try { it.join(500) } catch (e: InterruptedException) {} }
        thread = null
        onEnded()
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val size = maxOf(min, block * 2 * 4 * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(size).build()
    }

    private fun pump(track: AudioTrack) {
        val buf = FloatArray(block * 2)
        val ts = AudioTimestamp()
        var written = 0L
        var n = 0
        try {
            track.play()
            while (running) {
                synchronized(lock) { mixer.render(buf, block) }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                written += block
                if (++n % 50 == 0 && track.getTimestamp(ts)) {   // every ~250 ms
                    val playedNow = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sr / 1_000_000_000L
                    val ms = (written - playedNow) * 1000.0 / sr
                    if (ms in 0.0..1000.0) latency = if (latency == 0.0) ms else latency * 0.8 + ms * 0.2
                }
            }
        } catch (e: Throwable) {
            Log.e("TELL", "audio thread failed", e)
        } finally {
            try { track.stop() } catch (e: Exception) {}
            track.release()
        }
    }
}
