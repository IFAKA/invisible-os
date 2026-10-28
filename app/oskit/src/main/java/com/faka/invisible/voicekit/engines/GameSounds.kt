package com.faka.invisible.voicekit

import android.os.Handler

/**
 * ENGINE. The small sound desk of the simple games: a low-latency stream with a mixer. [play] puts a sound at a moment
 * ([delayMs] from now, sample-exact). Build the sounds with [warm] (background thread), then [open]; [close] when the game ends.
 */
class GameSounds {
    private val sr = Synth.SR
    private val lock = Any()
    private val mixer = FrameMixer()
    @Volatile private var running = false
    private val audio = GameAudio(sr, sr * 5 / 1000, { buf, n -> synchronized(lock) { mixer.render(buf, n) } }, { running }, {})

    /** Runs [build] on a background thread (the phone is slow at synth work), then [ready] on [handler]'s thread. */
    fun warm(handler: Handler, build: () -> Unit, ready: () -> Unit) {
        Thread({ try { build() } catch (e: Throwable) { android.util.Log.e("GAMESOUNDS", "build failed", e) }; handler.post(ready) }, "game-sounds-load").start()
    }

    /** False if the audio output could not be built. */
    fun open(): Boolean { running = true; if (!audio.open()) { running = false; return false }; audio.start(); return true }

    fun play(sound: FloatArray, pan: Double = 0.0, gain: Double = 0.5, delayMs: Long = 0) = synchronized(lock) {
        mixer.play(sound, mixer.nowFrame() + sr * delayMs / 1000, pan, gain)
    }

    /** Drops sounds not yet played. */
    fun clear() = synchronized(lock) { mixer.clear() }

    fun close() { running = false; audio.join() }
}
