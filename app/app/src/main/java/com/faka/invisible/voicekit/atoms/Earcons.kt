package com.faka.invisible.voicekit

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sound set (see SOUND-DESIGN.md). Pure code: every [Tone] becomes 16-bit mono PCM.
 * ONE material (a tuned wooden bar struck with a mallet: a few inharmonic partials, the high ones die fast, a tiny
 * noise knock at the start), ONE chord (C major triad, so nothing ever clashes), ONE rule:
 * notes going UP = in / yes / done, going DOWN = out / back / no, one note = information, a repeat = again.
 * Only the two UI clicks (tap, tick) use a dry wood block. Everything else gets a short room tail (reverb).
 * Loudness is set per tone in [LEVEL] (peak dBFS): UI feedback is far under the speech voice (peak about -2 dBFS), results are closer.
 */
object Earcons {
    const val SR = 44100
    /** Partials of a struck bar as (ratio to the pitch, weight, how much faster than the pitch it dies), the pitch decay [tau] in s, and the knock [hit]. */
    internal enum class V(val modes: List<Triple<Double, Double, Double>>, val tau: Double, val hit: Double) {
        MALLET(listOf(Triple(1.0, 1.0, 1.0), Triple(3.99, .22, 3.0), Triple(9.8, .05, 7.0)), .20, .10),
        LOW(listOf(Triple(1.0, 1.0, 1.0), Triple(2.0, .45, 1.6), Triple(4.0, .10, 3.0)), .14, .06),
        WOOD(listOf(Triple(1.0, 1.0, 1.0), Triple(2.4, .5, 1.5)), .008, .55),
    }
    internal class N(val v: V, val hz: Double, val startMs: Int, val ms: Int, val gain: Double)

    private val hz = mapOf("C4" to 261.63, "G4" to 392.0, "C5" to 523.25, "E5" to 659.25, "G5" to 783.99, "C6" to 1046.5, "E6" to 1318.5)

    private fun run(names: List<String>, step: Int, ms: Int, g: Double, v: V = V.MALLET) = names.mapIndexed { i, n -> N(v, hz.getValue(n), i * step, ms, g) }
    private fun one(name: String, ms: Int, g: Double, v: V = V.MALLET) = run(listOf(name), 0, ms, g, v)

    private val page = one("G5", 180, .22)   // a new screen: one quiet note; the speech names the place
    private val spec: Map<Tone, List<N>> = mapOf(
        Tone.TAP to one("C6", 20, .14, V.WOOD),
        Tone.TICK to one("G5", 25, .25, V.WOOD),
        Tone.HUB to run(listOf("C5", "G5"), 100, 450, .45),                 // open: up a fifth
        Tone.MESSAGE to page, Tone.TIMER to page, Tone.MORE to page, Tone.VOLUME to page,
        Tone.PICK to run(listOf("C5", "E5"), 55, 160, .40),                 // 1 click: up
        Tone.RESTART to run(listOf("G4", "C5", "E5"), 70, 200, .32),        // rises from below: the list starts over (also 2 clicks)
        Tone.AGAIN to run(listOf("C5", "E5"), 50, 120, .30),                // 2 clicks: a short step up = next now
        Tone.BACK to run(listOf("G5", "E5", "C5"), 60, 160, .35),           // 3 clicks: down
        Tone.UNDO to run(listOf("C5", "E5", "G5"), 70, 160, .22),           // quiet: you can still cancel
        Tone.SENT to run(listOf("C5", "G5", "C6"), 90, 700, .45),           // up, rings out: done
        Tone.CANCEL to run(listOf("G5", "C5"), 150, 300, .35),              // down: undone
        Tone.LEAVE to run(listOf("G5", "E5", "C5"), 130, 600, .30),         // slow down: bye
        Tone.EXIT to run(listOf("G5", "E5", "C5"), 130, 600, .30),
        Tone.UNKNOWN to one("G4", 260, .40, V.LOW),                         // flat and low: no (G4: C4 is too low for a phone speaker)
        Tone.DING to run(listOf("E5", "C5"), 200, 350, .22),                // end of list: soft fall
        Tone.ARRIVE to one("E5", 200, .25),
        Tone.ARRIVE_FAV to run(listOf("C5", "E5", "G5"), 90, 180, .35),
        Tone.ALARM to run(listOf("C5", "E5", "G5", "C6", "G5", "C6"), 140, 450, .40),
    )

    /** Peak level of each tone in dBFS. The speech clips peak at about -2. UI feedback is much quieter than results. */
    private val LEVEL = mapOf(
        Tone.TAP to -26.0, Tone.TICK to -24.0,
        Tone.MESSAGE to -20.0, Tone.TIMER to -20.0, Tone.MORE to -20.0, Tone.VOLUME to -20.0,
        Tone.PICK to -17.0, Tone.AGAIN to -18.0, Tone.BACK to -17.0, Tone.RESTART to -17.0, Tone.UNDO to -22.0,
        Tone.DING to -22.0, Tone.ARRIVE to -20.0, Tone.ARRIVE_FAV to -17.0,
        Tone.HUB to -14.0, Tone.SENT to -13.0, Tone.CANCEL to -17.0, Tone.LEAVE to -18.0, Tone.EXIT to -18.0,
        Tone.UNKNOWN to -16.0, Tone.ALARM to -10.0,
    )

    private val cache = HashMap<Tone, ShortArray>()

    /** PCM for [t]. */
    fun pcm(t: Tone): ShortArray = synchronized(cache) { cache.getOrPut(t) { render(spec.getValue(t), reverb = t != Tone.TAP && t != Tone.TICK, peakDb = LEVEL.getValue(t)) } }

    /** One note for volume level 1..4: the level is a position on the chord (C5 E5 G5 C6). */
    fun level(n: Int): ShortArray = render(one(listOf("C5", "E5", "G5", "C6")[(n - 1).coerceIn(0, 3)], 350, .40), true, -16.0)

    private fun voice(n: N, t: Double): Double {
        val tau = minOf(n.v.tau, n.ms / 3000.0)   // the note has fallen about 26 dB when its time is up: it fades, it is not cut
        var s = 0.0
        for ((ratio, w, speed) in n.v.modes) {
            val f = n.hz * ratio
            if (f < 6000) s += w * sin(2 * PI * f * t) * exp(-t * speed / tau)
        }
        return s * n.gain
    }

    private fun lowPass(x: DoubleArray, fc: Double): DoubleArray {
        val a = exp(-2 * PI * fc / SR); var y = 0.0
        return DoubleArray(x.size) { y = (1 - a) * x[it] + a * y; y }
    }

    /** Small Schroeder room: 3 combs + 1 allpass, about 12% wet, tail about 300 ms. Keeps sound from feeling dry or digital in an earbud. */
    private fun room(x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        for ((ms, fb) in listOf(29.7 to .55, 37.1 to .5, 41.1 to .45)) {
            val d = (ms * SR / 1000).toInt(); val c = DoubleArray(x.size)
            for (i in x.indices) { c[i] = x[i] + (if (i >= d) c[i - d] * fb else 0.0); y[i] += c[i] * .1 }
        }
        val ap = 5 * SR / 1000; val z = DoubleArray(y.size)   // allpass smears the echoes into a tail
        for (i in y.indices) z[i] = -.5 * y[i] + (if (i >= ap) y[i - ap] + .5 * z[i - ap] else 0.0)
        return DoubleArray(x.size) { x[it] + z[it] }
    }

    internal fun render(notes: List<N>, reverb: Boolean, peakDb: Double): ShortArray {
        val tail = if (reverb) 300 else 8
        val total = (notes.maxOf { it.startMs + it.ms } + tail) * SR / 1000
        var buf = DoubleArray(total)
        for (n in notes) {
            val o = n.startMs * SR / 1000; val len = n.ms * SR / 1000
            val atk = SR * 2 / 1000   // sharp but not a click: 2 ms
            val rel = SR * 25 / 1000
            val rnd = java.util.Random(n.hz.toLong() * 31 + n.startMs)
            val knock = lowPass(DoubleArray(SR * 3 / 1000) { (rnd.nextDouble() * 2 - 1) * exp(-it.toDouble() / (SR * .0008)) }, 5000.0)   // the mallet touching the bar
            for (i in 0 until len) {
                var s = voice(n, i.toDouble() / SR) + (if (i < knock.size) knock[i] * n.v.hit * 3 else 0.0) * n.gain
                if (i < atk) s *= .5 - .5 * kotlin.math.cos(PI * i / atk)
                if (len - 1 - i < rel) s *= (len - 1 - i).toDouble() / rel
                buf[o + i] += s
            }
        }
        if (reverb) buf = room(buf)
        val soft = lowPass(buf, 4500.0)               // no 3-6 kHz bite in an earbud
        val low = lowPass(soft, 200.0)
        buf = DoubleArray(total) { soft[it] - low[it] }   // nothing under 200 Hz: a phone speaker cannot play it anyway
        val fade = SR * minOf(20, tail) / 1000
        for (i in 0 until fade) buf[total - 1 - i] *= i.toDouble() / fade   // the very end never stops with a jump
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val k = Math.pow(10.0, peakDb / 20) / peak
        return ShortArray(total) { (buf[it] * k * 32767).toInt().coerceIn(-32767, 32767).toShort() }
    }
}

/** Sounds of the Write program (its own: the OS tones are not used inside a program). Same material as [Earcons]; pitch says which side. */
object TypeSounds {
    private fun seq(v: Earcons.V, stepMs: Int, ms: Int, vararg hz: Double) = hz.mapIndexed { i, f -> Earcons.N(v, f, i * stepMs, ms, .4) }

    fun pcm(c: TypeCue): ShortArray = when (c) {
        TypeCue.PICK_LEFT -> Earcons.render(seq(Earcons.V.MALLET, 0, 120, 880.0), true, -17.0)
        TypeCue.PICK_RIGHT -> Earcons.render(seq(Earcons.V.MALLET, 0, 120, 660.0), true, -17.0)
        TypeCue.NEXT -> Earcons.render(seq(Earcons.V.WOOD, 0, 25, 440.0), false, -24.0)
        TypeCue.LETTERS -> Earcons.render(seq(Earcons.V.MALLET, 70, 130, 330.0, 494.0), true, -20.0)   // up
        TypeCue.WORDS -> Earcons.render(seq(Earcons.V.MALLET, 70, 130, 494.0, 330.0), true, -20.0)     // down
        TypeCue.DELETE -> Earcons.render(seq(Earcons.V.LOW, 70, 160, 392.0, 262.0), true, -17.0)
        TypeCue.DONE -> Earcons.render(seq(Earcons.V.MALLET, 85, 400, 523.0, 659.0, 784.0), true, -14.0)
        TypeCue.ARMED -> Earcons.render(seq(Earcons.V.WOOD, 0, 25, 1000.0), false, -24.0)
        TypeCue.MENU -> Earcons.render(seq(Earcons.V.MALLET, 60, 110, 392.0, 392.0), true, -20.0)
    }
}
