@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ENGINE. The sound of the [LetterLoop]: words made by the speech engine (as clips), shortened to the loop speed, and played at exact
 * frames of one low-latency stereo stream ([GameAudio] + [FrameMixer]). The time an item is heard is the frame it is scheduled at
 * plus the delay the stream measures (buffer written minus buffer played, about every 250 ms); a fixed extra delay of the earbuds
 * is not known here: the click model learns it. No audio input of any kind.
 */
class LoopSound(
    private val words: WordClips,
    private val handler: Handler,
    /** True = the first voice plays on the right (these earbuds play the channels reversed; the app sets it from its option). */
    private val swapEars: () -> Boolean = { false },
    private val gain: Double = 0.7,
) : LoopAudio {
    override val sampleRate = 24000
    private val block = sampleRate * 5 / 1000
    private val lock = Any()
    private val mixer = FrameMixer()
    @Volatile private var running = false
    @Volatile private var latencyMs = 0.0
    private val audio = GameAudio(sampleRate, block, { buf, n -> synchronized(lock) { mixer.render(buf, n) } }, { running },
        { ms -> latencyMs = if (latencyMs == 0.0) ms else latencyMs * 0.8 + ms * 0.2 })
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "loop-clips").apply { isDaemon = true } }
    private val floats = ConcurrentHashMap<String, FloatArray>()
    private var seq = 0

    /** False if the audio output could not be built. Call [close] when done. */
    fun open(): Boolean {
        if (running) return true
        if (!audio.open()) return false
        running = true; latencyMs = 0.0; audio.start(); return true
    }

    fun close() { running = false; synchronized(lock) { mixer.clear() }; audio.join() }

    /** The measured output delay in ms (0 until measured). */
    val latency get() = latencyMs

    override fun prepare(texts: List<String>, then: (Map<String, FloatArray>) -> Unit) {
        val me = ++seq
        var left = texts.size
        var done = false
        val deliver = {
            if (!done && me == seq) {
                done = true
                worker.execute {   // shortening a clip takes some ms: off the main thread
                    val out = HashMap<String, FloatArray>()
                    for (t in texts) clipOf(t)?.let { out[t] = it }
                    handler.post { if (me == seq) then(out) }
                }
            }
        }
        if (texts.isEmpty()) { deliver(); return }
        for (t in texts) words.ensure(t) { if (--left == 0) deliver() }
        handler.postDelayed({ deliver() }, 5000)   // a voice that hangs must not hold the loop for ever: start with what is made
    }

    private fun clipOf(text: String): FloatArray? = floats[text] ?: try {
        words.fast(text, Pacing.LOOP_RATIO)?.let { p -> resample(p.samples, p.rate, sampleRate).also { floats[text] = it } }
    } catch (e: Throwable) { Log.e("Invisible", "loop clip failed", e); null }

    override fun nowFrame(): Long = synchronized(lock) { mixer.nowFrame() }
    override fun nowMs(): Long = SystemClock.uptimeMillis()

    override fun wallMsOf(frame: Long): Double {
        val f0: Long; val w0: Long
        synchronized(lock) { f0 = mixer.nowFrame(); w0 = SystemClock.uptimeMillis() }
        return w0 + (frame - f0) * 1000.0 / sampleRate + (if (latencyMs > 0) latencyMs else DEFAULT_LATENCY_MS)
    }

    override fun play(clip: FloatArray, atFrame: Long, pan: Double) {
        synchronized(lock) { mixer.play(clip, atFrame, if (swapEars()) -pan else pan, gain) }
    }

    override fun clear() { synchronized(lock) { mixer.clear() } }

    override fun confirm() {
        val pcm = Earcons.pcm(Tone.PICK)
        val f = resample(pcm, Earcons.SR, sampleRate)
        synchronized(lock) { mixer.play(f, mixer.nowFrame(), 0.0, 0.5) }
    }

    private fun resample(x: ShortArray, from: Int, to: Int): FloatArray {
        if (from == to) return FloatArray(x.size) { x[it] / 32768f }
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val p = i.toDouble() * from / to; val a = p.toInt(); val fr = (p - a).toFloat()
            val s0 = x[a.coerceAtMost(x.size - 1)] / 32768f; val s1 = x[(a + 1).coerceAtMost(x.size - 1)] / 32768f
            s0 + (s1 - s0) * fr
        }
    }

    private companion object { const val DEFAULT_LATENCY_MS = 80.0 }
}
