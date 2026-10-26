package com.faka.invisible.tell

import com.faka.invisible.voicekit.*

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Helpers shared by the TELL sound tests: FFT, pitch, centroid, rendering, WAV writing. */
object TellSoundTools {
    const val SR = 44100
    val SCALE_PC = intArrayOf(0, 3, 5, 7, 10)

    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Hann-windowed magnitude spectrum, zero padded to 2^17 (bin = SR/131072 = 0.34 Hz). */
    fun spectrum(x: FloatArray, size: Int = 1 shl 17): DoubleArray {
        val re = DoubleArray(size); val im = DoubleArray(size)
        val n = minOf(x.size, size)
        for (i in 0 until n) re[i] = x[i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
        fft(re, im)
        return DoubleArray(size / 2) { Math.hypot(re[it], im[it]) }
    }

    /** Frequency of the strongest peak (parabolic interpolation), ignoring below 30 Hz. */
    fun dominantHz(x: FloatArray): Double {
        val m = spectrum(x); val size = m.size * 2
        var best = (30.0 * size / SR).toInt()
        for (i in best until m.size - 1) if (m[i] > m[best]) best = i
        val a = Math.log(m[best - 1] + 1e-12); val b = Math.log(m[best] + 1e-12); val c = Math.log(m[best + 1] + 1e-12)
        val d = 0.5 * (a - c) / (a - 2 * b + c)
        return (best + d) * SR / size
    }

    fun centroidHz(x: FloatArray): Double {
        val m = spectrum(x); val size = m.size * 2
        var num = 0.0; var den = 0.0
        for (i in 1 until m.size) { num += i.toDouble() * SR / size * m[i]; den += m[i] }
        return num / den
    }

    /** Distance in cents to the nearest C minor pentatonic pitch (any octave). */
    fun centsFromScale(hz: Double): Double {
        val semis = 12 * log2(hz / 261.6256)
        var best = 1e9
        for (pc in SCALE_PC) for (k in -1..1) {
            val s = (semis - pc) / 12.0; val r = s - Math.floor(s + 0.5)   // octaves from the nearest octave of pc
            best = minOf(best, abs(r) * 1200.0)
        }
        return best
    }

    fun db(x: Double) = 20 * log10(max(x, 1e-12))
    fun peakDb(x: FloatArray, from: Int = 0, to: Int = x.size) = db((from until to).maxOfOrNull { abs(x[it]).toDouble() } ?: 0.0)

    /** Render [cue] on [side] until it is quiet: returns interleaved stereo. */
    fun renderCue(cue: Cue, side: Side, ms: Int = 1600): FloatArray {
        val m = TellMixer(); m.play(cue, side)
        val n = SR * ms / 1000; val out = FloatArray(n * 2); m.render(out, n); return out
    }
    fun channel(st: FloatArray, ch: Int) = FloatArray(st.size / 2) { st[2 * it + ch] }
    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]; return Math.sqrt(s / max(1, to - from))
    }

    fun wav(stereo: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(44 + stereo.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + stereo.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        bb.putInt(16).putShort(1).putShort(2).putInt(SR).putInt(SR * 4).putShort(4).putShort(16)
        bb.put("data".toByteArray()).putInt(stereo.size * 2)
        for (v in stereo) bb.putShort((v * 32767f).roundToInt().coerceIn(-32767, 32767).toShort())
        return bb.array()
    }
    fun write(f: File, stereo: FloatArray) { f.parentFile?.mkdirs(); f.writeBytes(wav(stereo)) }
}
