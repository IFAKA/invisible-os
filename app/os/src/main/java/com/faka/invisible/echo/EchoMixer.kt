package com.faka.invisible.echo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Sample-accurate scheduler: a sound starts at an exact frame, so the beat never drifts. Callers share one lock with the audio thread. */
class EchoMixer {
    private class Voice(val src: FloatArray, val start: Long, val l: Float, val r: Float)

    private val voices = ArrayList<Voice>()
    private var frames = 0L

    /** Frames already handed to the audio output. */
    fun nowFrame(): Long = frames
    fun active(): Int = voices.size
    /** Drop every sound not yet played (a program skips ahead). */
    fun clear() { voices.clear() }

    /** @param pan -1 hard left, 0 centre, 1 hard right. @param atFrame absolute (from [nowFrame]); in the past = now. */
    fun play(src: FloatArray, atFrame: Long, pan: Double = 0.0, gain: Double = 0.5) {
        val a = (pan + 1) * PI / 4
        voices.add(Voice(src, maxOf(atFrame, frames), (cos(a) * gain * 1.414).toFloat(), (sin(a) * gain * 1.414).toFloat()))
    }

    /** Interleaved stereo. */
    fun render(out: FloatArray, n: Int) {
        java.util.Arrays.fill(out, 0, n * 2, 0f)
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            val from = maxOf(0L, v.start - frames).toInt()
            var i = from
            while (i < n) {
                val k = (frames + i - v.start).toInt()
                if (k >= v.src.size) break
                out[2 * i] += v.src[k] * v.l; out[2 * i + 1] += v.src[k] * v.r
                i++
            }
            if (v.start + v.src.size <= frames + n) it.remove()
        }
        for (j in 0 until n * 2) out[j] = out[j].coerceIn(-1f, 1f)
        frames += n
    }
}
