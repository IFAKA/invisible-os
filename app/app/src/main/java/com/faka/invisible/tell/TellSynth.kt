package com.faka.invisible.tell

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every TELL sound as a mono FloatArray at 44100 Hz, peak exactly 1.0 (the mixer applies the loudness ladder and the pan).
 * Pure code, no Android types, fully deterministic (noise comes from a fixed-seed generator).
 * One scale: C minor pentatonic (C Eb F G Bb). Saw/square voices are built additively (harmonic sums with a smooth
 * low-pass shape), so they never alias and nothing goes above 8 kHz except the clang (6 kHz) and the hi-hat (9 kHz).
 * Every one-shot sample has a 4 ms raised-cosine fade at both ends (no clicks).
 */
object TellSynth {
    const val SR = 44100
    const val BEAT_FRAMES = 22050
    const val ROUND_FRAMES = 88200
    const val HUM_FRAMES = 22050
    private const val FADE = SR * 4 / 1000
    private const val TWO_PI = 2 * PI

    // ---- the scale ----
    private const val C = 0; private const val EB = 3; private const val F = 5; private const val G = 7; private const val BB = 10
    private fun hz(pc: Int, oct: Int) = 261.6256 * 2.0.pow((pc + 12 * (oct - 4)) / 12.0)
    /** Status pitch for energy 0..5: C3 Eb3 F3 G3 Bb3 C4. */
    val STATUS_HZ = doubleArrayOf(hz(C, 3), hz(EB, 3), hz(F, 3), hz(G, 3), hz(BB, 3), hz(C, 4))
    /** The scale as pitch classes (semitones above C). */
    val SCALE = intArrayOf(C, EB, F, G, BB)

    // ---- small tools ----
    private class Noise(seed: Int) {
        private var s = seed
        fun next(): Double { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); return s / 2147483648.0 }
    }
    private fun ms(m: Double) = (m * SR / 1000.0).toInt()
    private fun ex(t: Double, tau: Double) = exp(-t / tau)
    private fun coef(fc: Double) = 1.0 - exp(-TWO_PI * fc / SR)
    private fun lp(x: DoubleArray, fc: Double): DoubleArray { val a = coef(fc); var y = 0.0; return DoubleArray(x.size) { y += a * (x[it] - y); y } }
    private fun hp(x: DoubleArray, fc: Double): DoubleArray { val l = lp(x, fc); return DoubleArray(x.size) { x[it] - l[it] } }
    private fun noise(n: Int, seed: Int): DoubleArray { val r = Noise(seed); return DoubleArray(n) { r.next() } }

    /** Saw with a 2nd-order low-pass shape at fc: sum of harmonics 1/n, band limited to 8 kHz. */
    private fun saw(ph: Double, f: Double, fc: Double): Double {
        var s = 0.0; var n = 1; val lim = min(8000.0, 6 * fc)
        while (n * f <= lim) { val r = n * f / fc; s += sin(n * ph) / (n * sqrt(1 + r * r * r * r)); n++ }
        return s
    }
    private fun square(ph: Double, f: Double, fc: Double): Double {
        var s = 0.0; var n = 1; val lim = min(8000.0, 6 * fc)
        while (n * f <= lim) { val r = n * f / fc; s += sin(n * ph) / (n * sqrt(1 + r * r * r * r)); n += 2 }
        return s
    }
    /** Soft mallet / bell: sine plus quick-dying overtones (same octave family). */
    private fun mallet(f: Double, t: Double, tau: Double): Double {
        val w = TWO_PI * f * t
        return (sin(w) + .35 * sin(2 * w) * ex(t, .04) + .12 * sin(4 * w) * ex(t, .02)) * ex(t, tau)
    }
    /** Muted pluck: saw through a fast-closing filter. */
    private fun pluck(f: Double, t: Double): Double = saw(TWO_PI * f * t, f, 250 + 1500 * ex(t, .02)) * ex(t, .05)

    /** Soft room tail (3 combs + allpass, like Earcons) added after the sound; [tailMs] extra length. */
    private fun room(x: DoubleArray, wet: Double, tailMs: Int): DoubleArray {
        val n = x.size + ms(tailMs.toDouble())
        val xin = x.copyOf(n); val y = DoubleArray(n)
        for ((m, fb) in listOf(29.7 to .55, 37.1 to .5, 41.1 to .45)) {
            val d = ms(m); val c = DoubleArray(n)
            for (i in 0 until n) { c[i] = xin[i] + (if (i >= d) c[i - d] * fb else 0.0); y[i] += c[i] * wet }
        }
        val ap = ms(5.0); val z = DoubleArray(n)
        for (i in 0 until n) z[i] = -.5 * y[i] + (if (i >= ap) y[i - ap] + .5 * z[i - ap] else 0.0)
        val o = DoubleArray(n) { xin[it] + z[it] }
        return lp(o, 6000.0)
    }

    /** 4 ms fades at both ends, then peak 1.0. */
    private fun fin(x: DoubleArray, len: Int = x.size): FloatArray {
        val n = len; val f = min(FADE, n / 2)
        for (i in 0 until f) { val w = .5 - .5 * cos(PI * i / f); x[i] *= w; x[n - 1 - i] *= w }
        var p = 1e-9; for (i in 0 until n) p = max(p, abs(x[i]))
        return FloatArray(n) { (x[it] / p).toFloat() }
    }

    private fun kickTone(t: Double, f0: Double = 120.0, f1: Double = 55.0, drop: Double = .025): Double {
        // phase of a sine whose frequency falls exponentially f0 -> f1
        val ph = TWO_PI * (f1 * t + (f0 - f1) * drop * (1 - exp(-t / drop)))
        return sin(ph)
    }

    // ---- the cues ----
    private val cache = HashMap<Cue, FloatArray>()
    fun cue(c: Cue): FloatArray = synchronized(cache) { cache.getOrPut(c) { build(c) } }

    private fun build(c: Cue): FloatArray = when (c) {
        Cue.TAP -> tap()
        Cue.DETENT_1 -> detent1()
        Cue.DETENT_2 -> detent2()
        Cue.STAMP -> stamp()
        Cue.SNARE_CLOSE -> snare()
        Cue.KICK_ROLL -> kickRoll()
        Cue.JAB -> jab()
        Cue.GUARD -> guard()
        Cue.CHARGE -> charge()
        Cue.PARRY_CLANG -> clang()
        Cue.COIN -> coin()
        Cue.FIZZLE -> fizzle()
        Cue.CRACK_BOOM -> crackBoom()
        Cue.HIT -> hit()
        Cue.NOTHING -> nothing()
        Cue.WIN -> win()
        Cue.LOSS -> loss()
        Cue.DRAW -> draw()
        Cue.FORFEIT -> forfeit()
    }

    /** 20 ms wood tap, C6, soft 4 ms attack. */
    private fun tap(): FloatArray {
        val n = ms(20.0); val nz = lp(noise(n, 11), 3000.0)
        return fin(DoubleArray(n) { val t = it.toDouble() / SR; sin(TWO_PI * hz(C, 6) * t) * ex(t, .006) + .25 * nz[it] * ex(t, .003) })
    }
    private fun detent1(): FloatArray {
        val n = ms(30.0); val nz = lp(noise(n, 12), 3000.0)
        return fin(DoubleArray(n) { val t = it.toDouble() / SR; sin(TWO_PI * hz(G, 5) * t) * ex(t, .008) + .2 * nz[it] * ex(t, .004) })
    }
    /** Two notches, a little higher than the first, so the second is felt as "more". */
    private fun detent2(): FloatArray {
        val n = ms(52.0); val nz = lp(noise(n, 13), 3000.0); val d = ms(22.0)
        return fin(DoubleArray(n) { i ->
            val t = i.toDouble() / SR; val t2 = (i - d).toDouble() / SR
            sin(TWO_PI * hz(C, 6) * t) * ex(t, .007) * .8 + .2 * nz[i] * ex(t, .004) +
                if (i >= d) sin(TWO_PI * hz(G, 6) * t2) * ex(t2, .008) * .6 else 0.0
        })
    }
    /** 40 ms low thump with a tiny top click: "committed". */
    private fun stamp(): FloatArray {
        val n = ms(40.0)
        return fin(DoubleArray(n) { val t = it.toDouble() / SR; sin(TWO_PI * hz(G, 3) * t) * ex(t, .014) + .25 * sin(TWO_PI * hz(C, 5) * t) * ex(t, .004) })
    }
    /** Clap: three quick noise bursts then a body, short room. The window closed. */
    private fun snare(): FloatArray {
        val n = ms(210.0); val nz = lp(hp(noise(n, 14), 700.0), 5500.0)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR; var s = 0.0
            for ((k, off) in listOf(0.0, .011, .022).withIndex()) { val u = t - off; if (u >= 0) s += nz[i] * ex(u, if (k < 2) .006 else .045) * (if (k < 2) .7 else 1.0) }
            s + .35 * sin(TWO_PI * hz(G, 3) * t) * ex(t, .05)
        }
        return fin(room(x, .18, 120))
    }
    /** Eight kicks that speed up and grow, with a rising noise swell under them. */
    private fun kickRoll(): FloatArray {
        val n = ms(1100.0); val x = DoubleArray(n)
        var tk = 0.0; var gap = .22; var g = .4
        val starts = ArrayList<Pair<Double, Double>>()
        while (tk < .93) { starts.add(tk to g); tk += gap; gap *= .8; g = min(1.0, g + .09) }
        for ((s, gain) in starts) {
            val i0 = ms(s * 1000); val len = min(ms(170.0), n - i0)
            for (i in 0 until len) { val t = i.toDouble() / SR; x[i0 + i] += kickTone(t, 130.0, 55.0, .03) * ex(t, .055) * gain }
        }
        val nz = noise(n, 15)
        var y = 0.0
        for (i in 0 until n) { val u = i.toDouble() / ms(950.0); val a = coef(500 + 3500 * min(1.0, u)); y += a * (nz[i] - y); x[i] += y * .5 * min(1.0, u).pow(2.0) * (if (u > 1) ex((u - 1) * .95, .05) else 1.0) }
        return fin(x)
    }

    /** G4 saw pluck, fast filter envelope, 110 ms, plus a tiny soft click. */
    private fun jab(): FloatArray {
        val n = ms(110.0); val f = hz(G, 4); val nz = lp(noise(n, 16), 6000.0)
        var ph = 0.0
        return fin(DoubleArray(n) { i ->
            val t = i.toDouble() / SR; ph += TWO_PI * f / SR
            saw(ph, f, 1500 + 4500 * ex(t, .03)) * ex(t, .05) + .5 * nz[i] * ex(t, .006)
        })
    }
    /** Two detuned C3 sines (+0.3%), a little C4, a noise puff: round and low. */
    private fun guard(): FloatArray {
        val n = ms(220.0); val nz = lp(hp(noise(n, 17), 300.0), 1800.0)
        return fin(DoubleArray(n) { i ->
            val t = i.toDouble() / SR; val f = hz(C, 3)
            val body = sin(TWO_PI * f * t) + sin(TWO_PI * f * 1.003 * t) + .5 * sin(TWO_PI * 2 * f * t)
            body * ex(t, .12) + nz[i] * ex(t, .018) * 0.22
        })
    }
    /** Riser: saw C3 -> C4 over 500 ms, growing brightness and noise; sub boom (F1 = 43.65 Hz) from 480 ms for 250 ms. */
    private fun charge(): FloatArray {
        val riseN = ms(500.0); val n = ms(740.0); val x = DoubleArray(n)
        val nz = noise(n, 18); var ph = 0.0; var y = 0.0
        val f0 = hz(C, 3)
        for (i in 0 until riseN) {
            val u = i.toDouble() / riseN; val t = i.toDouble() / SR
            val f = f0 * 2.0.pow(u); ph += TWO_PI * f / SR
            val a = coef(1200 + 6000 * u * u); y += a * (nz[i] - y)
            val gate = if (u > .96) (1 - u) / .04 else 1.0
            x[i] = (saw(ph, f, 800 + 7000 * u * u) * (.35 + .65 * u) * .55 + y * 0.2 * u.pow(1.5)) * gate
            if (t < 0) x[i] = 0.0
        }
        val b0 = ms(480.0)
        for (i in b0 until n) {
            val t = (i - b0).toDouble() / SR
            x[i] += (1.5 * kickTone(t, 90.0, hz(F, 1), .03) * ex(t, .09) + .5 * sin(TWO_PI * hz(F, 2) * t) * ex(t, .07)) * 0.9
        }
        return fin(x)
    }
    /** High bell on Eb6 with its octave family, 6 kHz at most, short room. */
    private fun clang(): FloatArray {
        val n = ms(420.0); val nz = lp(hp(noise(n, 19), 2500.0), 6000.0); val f = hz(EB, 6)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            (sin(TWO_PI * f * t) * ex(t, .16) + .6 * sin(TWO_PI * 2 * f * t) * ex(t, .09) + .35 * sin(TWO_PI * 3 * f * t) * ex(t, .05) +
                .15 * sin(TWO_PI * 4 * f * t) * ex(t, .03)) + .35 * nz[i] * ex(t, .004)
        }
        return fin(room(x, .2, 150))
    }
    /** Quick up-sweep: G5 jumps up to C6. "+1 energy". */
    private fun coin(): FloatArray {
        val n = ms(280.0); var ph = 0.0
        val f1 = hz(G, 5); val f2 = hz(C, 6)
        return fin(DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            val u = ((t - .055) / .02).coerceIn(0.0, 1.0)
            val f = f1 * (f2 / f1).pow(u); ph += TWO_PI * f / SR
            val env = if (t < .055) 1.0 else ex(t - .055, .08) * 1.0
            square(ph, f, 3500.0) * env
        })
    }
    /** The charge riser falls in pitch and breaks into noise. */
    private fun fizzle(): FloatArray {
        val n = ms(400.0); val nz = noise(n, 20); var ph = 0.0; var y = 0.0
        return fin(DoubleArray(n) { i ->
            val t = i.toDouble() / SR; val u = min(1.0, t / .25)
            val f = hz(C, 5) * 2.0.pow(-3 * u); ph += TWO_PI * f / SR
            val tone = saw(ph, f, 400 + 2500 * (1 - u)) * (1 - u) * .8
            val a = coef(5000 * ex(t, .09) + 300); y += a * (nz[i] - y)
            val nzEnv = min(1.0, t / .08) * ex(max(0.0, t - .08), .09)
            tone + y * 1.6 * nzEnv
        })
    }
    /** Crack plus the heaviest sub boom of the game (F1), long, short room. */
    private fun crackBoom(): FloatArray {
        val n = ms(520.0); val nz = lp(hp(noise(n, 21), 500.0), 7000.0); val rum = lp(noise(n, 22), 200.0)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            .7 * nz[i] * ex(t, .012) +
                1.0 * kickTone(t, 110.0, hz(F, 1), .035) * ex(t, .17) +
                .5 * sin(TWO_PI * hz(F, 2) * t) * ex(t, .09) + 3.0 * rum[i] * ex(t, .2)
        }
        return fin(room(x, .12, 160))
    }
    /** Low thud F2 plus a 120 ms down-slide in a low-pass over a little noise. */
    private fun hit(): FloatArray {
        val n = ms(230.0); val nz = noise(n, 23); var y = 0.0
        return fin(DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            val a = coef(150 + 1850 * ex(t, .035)); y += a * (nz[i] - y)
            val slide = if (t < .12) 1.0 else ex(t - .12, .03)
            (sin(TWO_PI * hz(F, 2) * t) * ex(t, .07) + .3 * sin(TWO_PI * hz(F, 3) * t) * ex(t, .03)) + 1.8 * y * ex(t, .05) * slide
        })
    }
    /** Dull tick: nothing happened. */
    private fun nothing(): FloatArray {
        val n = ms(45.0); val nz = lp(noise(n, 24), 700.0)
        return fin(DoubleArray(n) { i -> val t = i.toDouble() / SR; sin(TWO_PI * hz(G, 3) * t) * ex(t, .012) + 1.2 * nz[i] * ex(t, .008) })
    }

    private fun notesOver(n: Int, starts: List<Int>, len: (Int) -> Int, voice: (Int, Double, Double) -> Double): DoubleArray {
        val x = DoubleArray(n)
        for ((k, s) in starts.withIndex()) {
            val l = min(len(k), n - s)
            for (i in 0 until l) {
                val t = i.toDouble() / SR; var v = voice(k, t, i.toDouble())
                val r = l - 1 - i; if (r < FADE) v *= r.toDouble() / FADE   // each note ends softly
                if (i < FADE) v *= .5 - .5 * cos(PI * i / FADE)
                x[s + i] += v
            }
        }
        return x
    }
    private fun cut600(x: DoubleArray): DoubleArray = x.copyOf(ms(600.0))

    /** Three rising bell notes C4 Eb4 G4, then C5 rings; 600 ms. */
    private fun win(): FloatArray {
        val fs = doubleArrayOf(hz(C, 4), hz(EB, 4), hz(G, 4), hz(C, 5))
        val x = notesOver(ms(600.0), listOf(0, ms(100.0), ms(200.0), ms(300.0)), { k -> if (k < 3) ms(160.0) else ms(300.0) }) { k, t, _ -> mallet(fs[k], t, if (k < 3) .09 else .14) * (if (k < 3) .75 else 1.0) }
        return fin(cut600(room(x, .15, 60)))
    }
    /** Three falling notes G4 Eb4 C4, then C3 low and dark (low-passed saw); 600 ms. */
    private fun loss(): FloatArray {
        val fs = doubleArrayOf(hz(G, 4), hz(EB, 4), hz(C, 4), hz(C, 3))
        val x = notesOver(ms(600.0), listOf(0, ms(100.0), ms(200.0), ms(300.0)), { k -> if (k < 3) ms(160.0) else ms(300.0) }) { k, t, _ ->
            saw(TWO_PI * fs[k] * t, fs[k], if (k < 3) 900.0 else 500.0) * ex(t, if (k < 3) .09 else .16) * (if (k < 3) .8 else 1.0)
        }
        return fin(cut600(lp(room(x, .12, 60), 2500.0)))
    }
    /** Two flat notes at the same pitch (G3). */
    private fun draw(): FloatArray {
        val x = notesOver(ms(600.0), listOf(0, ms(220.0)), { k -> if (k == 0) ms(220.0) else ms(380.0) }) { _, t, _ -> mallet(hz(G, 3), t, .13) }
        return fin(cut600(room(x, .15, 60)))
    }
    /** Tape stop: a C2/G2/C3 chord whose pitch falls to a halt in 400 ms. */
    private fun forfeit(): FloatArray {
        val n = ms(420.0); val T = .40; val x = DoubleArray(n)
        val fs = doubleArrayOf(hz(C, 2), hz(G, 2), hz(C, 3)); val ph = DoubleArray(3)
        for (i in 0 until n) {
            val t = i.toDouble() / SR; val u = min(1.0, t / T); val r = (1 - u).pow(1.6)
            var s = 0.0
            for (k in 0 until 3) { ph[k] += TWO_PI * fs[k] * r / SR; s += saw(ph[k], fs[k] * max(r, .05), 600 + 900 * r) }
            x[i] = s * (1 - u).pow(.5)
        }
        return fin(x)
    }

    // ---- status and life ----
    private val statusCache = HashMap<Int, FloatArray>()
    /** 150 ms note for energy 0..5. Own = bell/mallet, opponent = muted pluck. Energy >= 3 adds the shimmer (2x trill, 8 Hz wobble). */
    fun statusNote(energy: Int, own: Boolean): FloatArray = synchronized(statusCache) {
        val e = energy.coerceIn(0, 5)
        statusCache.getOrPut(e * 2 + (if (own) 1 else 0)) {
            val n = ms(150.0); val f = STATUS_HZ[e]
            fin(DoubleArray(n) { i ->
                val t = i.toDouble() / SR
                var s = if (own) mallet(f, t, .08) else pluck(f, t) * 1.2
                if (e >= 3) s += .4 * sin(TWO_PI * 2 * f * t) * (.5 + .5 * sin(TWO_PI * 8 * t)) * ex(t, .12)
                s
            })
        }
    }
    /** Life notes: [life] short notes 70 ms apart, descending, always ending on C4 (4: G F Eb C). */
    fun lifeNotes(life: Int, own: Boolean): FloatArray = synchronized(statusCache) {
        val l = life.coerceIn(1, 4)
        statusCache.getOrPut(100 + l * 2 + (if (own) 1 else 0)) {
            val seq = doubleArrayOf(hz(G, 4), hz(F, 4), hz(EB, 4), hz(C, 4)).takeLast(l)
            val n = ms(70.0) * (l - 1) + ms(70.0)
            val x = notesOver(n, List(l) { it * ms(70.0) }, { ms(70.0) }) { k, t, _ -> if (own) mallet(seq[k], t, .035) else pluck(seq[k], t) }
            fin(x)
        }
    }

    // ---- holds (loopable) ----
    private val humCache = HashMap<Int, FloatArray>()
    /**
     * The hold hum, exactly [HUM_FRAMES] (0.5 s) long and seamless in a loop: every partial has a whole number of cycles
     * (multiples of 2 Hz). Level 1 = guard hum around C3 (130 Hz); level 2 = an octave up (260 Hz) with a faint shimmer.
     * No edge fades (the mixer ramps the start and the end). Peak 1.0.
     */
    fun holdHum(level: Int): FloatArray = synchronized(humCache) {
        humCache.getOrPut(if (level >= 2) 2 else 1) {
            val l2 = level >= 2; val b = if (l2) 260.0 else 130.0
            val x = DoubleArray(HUM_FRAMES) { i ->
                val t = i.toDouble() / SR
                val wob = 1 + .12 * sin(TWO_PI * (if (l2) 6.0 else 4.0) * t)
                (sin(TWO_PI * b * t) + .5 * sin(TWO_PI * (b + 2) * t) + .35 * sin(TWO_PI * 2 * b * t) + .12 * sin(TWO_PI * 3 * b * t) +
                    if (l2) .12 * sin(TWO_PI * 6 * b * t) else 0.0) * wob
            }
            var p = 1e-9; for (v in x) p = max(p, abs(v))
            FloatArray(HUM_FRAMES) { (x[it] / p).toFloat() }
        }
    }
    /** The faint riser that starts at 800 ms of a hold: a filtered saw gliding C4 -> C5 over 500 ms. */
    fun holdRiser(): FloatArray = synchronized(cache) {
        riserCache ?: run {
            val n = ms(500.0); var ph = 0.0
            fin(DoubleArray(n) { i -> val u = i.toDouble() / n; val f = hz(C, 4) * 2.0.pow(u); ph += TWO_PI * f / SR; saw(ph, f, 700 + 1500 * u) * (.3 + .7 * u) }).also { riserCache = it }
        }
    }
    private var riserCache: FloatArray? = null

    /** The held bass note at match end: C2, 1.4 s, slow decay. */
    fun bassHold(): FloatArray = synchronized(cache) {
        holdCache ?: run {
            val n = ms(1400.0); val f = hz(C, 2)
            fin(DoubleArray(n) { i -> val t = i.toDouble() / SR; (sin(TWO_PI * f * t) + .45 * sin(TWO_PI * 2 * f * t) + .15 * sin(TWO_PI * 3 * f * t)) * ex(t, .7) }).also { holdCache = it }
        }
    }
    private var holdCache: FloatArray? = null

    // ---- the bed ----
    /**
     * One round (4 beats, 2000 ms, 88200 frames) of each layer; all loop exactly. Scaled so that kick + hat + bass
     * (tension 0) peak at exactly 1.0. [bright] (tension >= 1) and [ostinato] (tension 2) are scaled relative to that.
     */
    class Bed(val kick: FloatArray, val hat: FloatArray, val bass: FloatArray, val bright: FloatArray, val ostinato: FloatArray)

    private var bedCache: Bed? = null
    fun bed(): Bed = synchronized(cache) { bedCache ?: buildBed().also { bedCache = it } }

    private fun buildBed(): Bed {
        val N = ROUND_FRAMES
        val kick = DoubleArray(N); val hat = DoubleArray(N); val bass = DoubleArray(N); val bright = DoubleArray(N); val ost = DoubleArray(N)
        // kick: soft sub, 55 Hz, 90 ms body, beat 1 accented
        for (b in 0 until 4) {
            val g = if (b == 0) 1.0 else .72; val i0 = b * BEAT_FRAMES; val len = ms(130.0)
            for (i in 0 until len) {
                val t = i.toDouble() / SR; var v = kickTone(t, 100.0, 55.0, .02) * ex(t, .035) * g
                if (i < FADE) v *= .5 - .5 * cos(PI * i / FADE); val r = len - 1 - i; if (r < FADE) v *= r.toDouble() / FADE
                kick[i0 + i] += v
            }
        }
        // hat: off-beat, high-passed noise, up to 9 kHz
        val nz = lp(hp(noise(N, 31), 6500.0), 9000.0)
        for (b in 0 until 4) {
            val i0 = b * BEAT_FRAMES + BEAT_FRAMES / 2; val len = ms(40.0)
            for (i in 0 until len) {
                var v = nz[i0 + i] * ex(i.toDouble() / SR, .009)
                if (i < FADE) v *= .5 - .5 * cos(PI * i / FADE); val r = len - 1 - i; if (r < FADE) v *= r.toDouble() / FADE
                hat[i0 + i] += v
            }
        }
        // bass: eighth notes C C C G | C C G C, ducked under the kick (a small pump)
        val pat = intArrayOf(0, 0, 0, 1, 0, 0, 1, 0)
        val eighth = BEAT_FRAMES / 2
        for (k in 0 until 8) {
            val f = if (pat[k] == 0) hz(C, 2) else hz(G, 2); val i0 = k * eighth; val len = (eighth * .78).toInt()
            var ph = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / SR; ph += TWO_PI * f / SR
                var v = (sin(ph) + .3 * sin(2 * ph) + .12 * sin(3 * ph)) * (.55 + .45 * ex(t, .08))
                var w = saw(ph, f, 900.0) * (.6 + .4 * ex(t, .05))   // brighter copy, used from tension 1
                val sinceBeat = ((i0 + i) % BEAT_FRAMES).toDouble() / SR
                val duck = 1 - .55 * ex(sinceBeat, .05); v *= duck; w *= duck
                val env = (if (i < FADE) .5 - .5 * cos(PI * i / FADE) else 1.0) * (if (len - 1 - i < FADE) (len - 1 - i).toDouble() / FADE else 1.0)
                bass[i0 + i] += v * env; bright[i0 + i] += w * env
            }
        }
        // ostinato: 16ths on G4 / Bb4, plucked square, quiet
        val opat = intArrayOf(0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1)
        for (k in 0 until 16) {
            val f = if (opat[k] == 0) hz(G, 4) else hz(BB, 4); val i0 = (k * N / 16.0).toInt(); val len = ms(75.0); var ph = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / SR; ph += TWO_PI * f / SR
                var v = square(ph, f, 1800.0) * ex(t, .03)
                if (i < FADE) v *= .5 - .5 * cos(PI * i / FADE); val r = len - 1 - i; if (r < FADE) v *= r.toDouble() / FADE
                ost[i0 + i] += v
            }
        }
        fun pk(a: DoubleArray) = a.maxOf { abs(it) }
        // relative levels inside the tension-0 mix: kick .75, bass .62, hat .3 (hat -10 dB below the bed); then scale to peak 1
        val kk = .75 / pk(kick); val hh = .30 / pk(hat); val bb = .62 / pk(bass)
        var peak = 1e-9
        for (i in 0 until N) peak = max(peak, abs(kick[i] * kk + hat[i] * hh + bass[i] * bb))
        val s = 1 / peak
        return Bed(
            FloatArray(N) { (kick[it] * kk * s).toFloat() }, FloatArray(N) { (hat[it] * hh * s).toFloat() },
            FloatArray(N) { (bass[it] * bb * s).toFloat() },
            FloatArray(N) { (bright[it] * .45 / pk(bright) * s).toFloat() },
            FloatArray(N) { (ost[it] * .30 / pk(ost) * s).toFloat() },
        )
    }

    @Volatile var warmed = false

    /** Build everything now (call once, off the audio thread). Idempotent; a second caller waits for the first. */
    @Synchronized fun warm() {
        if (warmed) return
        for (c in Cue.values()) cue(c)
        for (e in 0..5) { statusNote(e, true); statusNote(e, false) }
        for (l in 1..4) { lifeNotes(l, true); lifeNotes(l, false) }
        holdHum(1); holdHum(2); holdRiser(); bassHold(); bed()
        warmed = true
    }

    /** Start the build in the background (low priority) so the sounds are ready before the player opens the Duel. */
    fun warmAsync() {
        if (warmed) return
        Thread({ try { val t0 = System.nanoTime(); warm(); android.util.Log.d("TELL", "warm took ${(System.nanoTime() - t0) / 1_000_000} ms") } catch (e: Throwable) { android.util.Log.e("TELL", "warm failed", e) } }, "tell-warm")
            .also { it.priority = Thread.MIN_PRIORITY; it.start() }
    }
}
