package com.faka.invisible

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spearcon = a spoken option sped up until it is no longer speech (Walker & Nance 2006). Once you know an option you
 * recognise its spearcon faster than the full word. Pure code: WAV in, shorter PCM out, pitch unchanged (WSOLA).
 */
object Spearcon {
    class Pcm(val rate: Int, val samples: ShortArray)

    /** Reads a 16-bit mono PCM WAV as written by Android's TextToSpeech.synthesizeToFile. Null if it is not that. */
    fun readWav(b: ByteArray): Pcm? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12; var rate = 0; var ch = 0; var bits = 0
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val n = bb.getInt(p + 4)
            if (id == "fmt ") { ch = bb.getShort(p + 10).toInt(); rate = bb.getInt(p + 12); bits = bb.getShort(p + 22).toInt() }
            if (id == "data") {
                if (ch != 1 || bits != 16 || rate <= 0) return null
                val len = minOf(n, b.size - p - 8) / 2
                return Pcm(rate, ShortArray(len) { bb.getShort(p + 8 + it * 2) })
            }
            p += 8 + n + (n and 1)
        }
        return null
    }

    /** Output length = [ratio] x input length (0.4 = 2.5x faster), same pitch. Trims leading and trailing silence first. */
    fun compress(x: ShortArray, rate: Int, ratio: Double = 0.4): ShortArray {
        val t = trim(x)
        if (t.size < rate / 20) return t
        val win = rate * 25 / 1000; val hop = win / 2           // 25 ms windows, 50% overlap in the output
        val inHop = (hop / ratio).toInt()                       // we advance faster through the input than the output
        val outLen = (t.size * ratio).toInt()
        val out = DoubleArray(outLen + win); val norm = DoubleArray(outLen + win)
        val hann = DoubleArray(win) { 0.5 - 0.5 * Math.cos(2 * Math.PI * it / (win - 1)) }
        val seek = rate * 8 / 1000                              // search +-8 ms for the best-matching start
        var o = 0; var ip = 0
        while (o + win <= out.size && ip + win + seek < t.size) {
            var best = ip
            if (o > 0) {                                        // pick the start whose first samples best continue the last output
                var bc = Double.NEGATIVE_INFINITY
                for (s in maxOf(0, ip - seek)..minOf(t.size - win - 1, ip + seek) step 4) {
                    var c = 0.0
                    for (k in 0 until hop step 2) c += t[s + k].toDouble() * (out[o + k] / maxOf(norm[o + k], 1e-6))
                    if (c > bc) { bc = c; best = s }
                }
            }
            for (k in 0 until win) { out[o + k] += t[best + k] * hann[k]; norm[o + k] += hann[k] }
            o += hop; ip += inHop
        }
        return ShortArray(outLen) { (out[it] / maxOf(norm[it], 1e-6)).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun trim(x: ShortArray, thr: Int = 400): ShortArray {
        var a = 0; var b = x.size
        while (a < b && kotlin.math.abs(x[a].toInt()) < thr) a++
        while (b > a && kotlin.math.abs(x[b - 1].toInt()) < thr) b--
        return x.copyOfRange(a, b)
    }

    /** Cache file name for an option text and language. */
    fun key(text: String, lang: String?) = "sp_" + (lang ?: "-") + "_" + text.hashCode().toUInt().toString(16)
}
