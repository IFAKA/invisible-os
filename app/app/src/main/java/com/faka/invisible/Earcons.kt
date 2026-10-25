package com.faka.invisible

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sound set (see SOUND-DESIGN.md). Pure code: every [Tone] becomes 16-bit mono PCM.
 * ONE voice (a soft mallet), ONE chord (C major triad, so nothing ever clashes), ONE rule:
 * notes going UP = in / yes / done, going DOWN = out / back / no, one note = information, a repeat = again.
 * Only the two UI clicks (tap, tick) use a dry wood sound. Everything else gets a short room tail (reverb).
 */
object Earcons {
    const val SR = 44100
    private enum class V { MALLET, LOW, WOOD }
    private class N(val v: V, val hz: Double, val startMs: Int, val ms: Int, val gain: Double)

    private val hz = mapOf("C4" to 261.63, "G4" to 392.0, "C5" to 523.25, "E5" to 659.25, "G5" to 783.99, "C6" to 1046.5, "E6" to 1318.5)

    private fun run(names: List<String>, step: Int, ms: Int, g: Double, v: V = V.MALLET) = names.mapIndexed { i, n -> N(v, hz.getValue(n), i * step, ms, g) }
    private fun one(name: String, ms: Int, g: Double, v: V = V.MALLET) = run(listOf(name), 0, ms, g, v)

    // Loudness ladder: tap -17 dB .. results -7 dB (relative); the speech voice is louder than all of them.
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
        Tone.UNKNOWN to one("C4", 260, .40, V.LOW),                         // flat and low: no
        Tone.DING to run(listOf("E5", "C5"), 200, 350, .22),                // end of list: soft fall
        Tone.ARRIVE to one("E5", 200, .25),
        Tone.ARRIVE_FAV to run(listOf("C5", "E5", "G5"), 90, 180, .35),
        Tone.ALARM to run(listOf("C5", "E5", "G5", "C6", "G5", "C6"), 140, 450, .40),
    )

    private val cache = HashMap<Tone, ShortArray>()

    /** PCM for [t]; peak below 0.9 of full scale. */
    fun pcm(t: Tone): ShortArray = synchronized(cache) { cache.getOrPut(t) { render(spec.getValue(t), reverb = t != Tone.TAP && t != Tone.TICK) } }

    /** One note for volume level 1..4: the level is a position on the chord (C5 E5 G5 C6). */
    fun level(n: Int): ShortArray = render(one(listOf("C5", "E5", "G5", "C6")[(n - 1).coerceIn(0, 3)], 350, .40), true)

    private fun voice(n: N, t: Double): Double {
        val w = 2 * PI * n.hz * t
        return when (n.v) {
            V.MALLET -> (sin(w) + .35 * sin(2 * w) * exp(-t / .04) + .12 * sin(4 * w) * exp(-t / .02)) * exp(-t / .2)
            V.LOW -> sin(w) * exp(-t / .12)
            V.WOOD -> sin(w) * exp(-t / .006)
        } * n.gain
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

    private fun render(notes: List<N>, reverb: Boolean): ShortArray {
        val tail = if (reverb) 300 else 0
        val total = (notes.maxOf { it.startMs + it.ms } + tail) * SR / 1000
        var buf = DoubleArray(total)
        for (n in notes) {
            val o = n.startMs * SR / 1000; val len = n.ms * SR / 1000
            val atk = if (n.v == V.WOOD) SR * 2 / 1000 else SR * 6 / 1000   // soft 6 ms attack: no click
            val rel = SR * 10 / 1000
            for (i in 0 until len) {
                var s = voice(n, i.toDouble() / SR)
                if (i < atk) s *= .5 - .5 * kotlin.math.cos(PI * i / atk)
                if (len - 1 - i < rel) s *= (len - 1 - i).toDouble() / rel
                buf[o + i] += s
            }
        }
        if (reverb) buf = room(buf)
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val k = minOf(1.0, .85 / peak)
        return ShortArray(total) { (buf[it] * k * 32767).toInt().coerceIn(-32767, 32767).toShort() }
    }
}

/** Sounds of the Write program (its own: the OS tones are not used inside a program). Short tones; pitch says which side. */
object TypeSounds {
    private fun tone(vararg notes: Pair<Double, Int>): ShortArray {   // (Hz, ms) one after another
        val out = ArrayList<Short>()
        for ((hz, ms) in notes) {
            val n = Earcons.SR * ms / 1000
            for (i in 0 until n) {
                val env = minOf(1.0, i / (Earcons.SR * 0.004)) * minOf(1.0, (n - i) / (Earcons.SR * 0.012))
                out.add((Math.sin(2 * Math.PI * hz * i / Earcons.SR) * env * 9000).toInt().toShort())
            }
        }
        return out.toShortArray()
    }

    fun pcm(c: TypeCue): ShortArray = when (c) {
        TypeCue.PICK_LEFT -> tone(880.0 to 70)
        TypeCue.PICK_RIGHT -> tone(660.0 to 70)
        TypeCue.NEXT -> tone(440.0 to 40)
        TypeCue.LETTERS -> tone(330.0 to 60, 494.0 to 60)
        TypeCue.WORDS -> tone(494.0 to 60, 330.0 to 60)
        TypeCue.DELETE -> tone(300.0 to 60, 200.0 to 80)
        TypeCue.DONE -> tone(523.0 to 80, 659.0 to 80, 784.0 to 140)
        TypeCue.ARMED -> tone(1000.0 to 25)
        TypeCue.MENU -> tone(392.0 to 50, 392.0 to 50)
    }
}
