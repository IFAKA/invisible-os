package com.faka.invisible.voicekit

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** ATOM. The two sound-building blocks the game programs share (pure maths, no Android): one decaying tone, and two sounds joined with a gap. */
object Synth {
    const val SR = 44100

    fun tone(freq: Double, ms: Int, decayMs: Double, overtone: Double = 0.0, attackMs: Double = 1.0): FloatArray {
        val n = SR * ms / 1000
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            val env = exp(-t * 1000.0 / decayMs) * minOf(1.0, t * 1000.0 / attackMs)
            ((sin(2 * PI * freq * t) + overtone * sin(2 * PI * freq * 2.76 * t)) * env / (1 + overtone)).toFloat()
        }
    }

    fun then(a: FloatArray, b: FloatArray, gapMs: Int): FloatArray {
        val off = SR * gapMs / 1000
        val out = FloatArray(maxOf(a.size, off + b.size))
        a.copyInto(out); for (i in b.indices) out[off + i] += b[i]
        return out
    }
}
