package com.faka.invisible.tell

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Voices, binaural pan, the bed, a -1 dBFS limiter. Pure and deterministic: the output depends only on the calls and the
 * number of frames rendered so far (no clock, no randomness), and not on the block sizes. Not thread safe: call everything
 * from one thread (or lock outside). Output is interleaved stereo float, 44.1 kHz.
 *
 * Pan (see GAME-TELL.md): ME = centre, OPP = hard left (-0.8) and 6 dB quieter in power (equal-power law, 5 kHz low-pass on
 * the source). The far ear is delayed by up to 0.6 ms (full at |pan| >= 0.8) and low-passed (3 kHz at |pan| >= 0.8, scaled
 * by the pan amount, off at centre). The soft limiter is a smooth waveshaper above 0.7 that never passes -1 dBFS.
 */
class TellMixer(val sampleRate: Int = 44100) {
    companion object {
        const val MAX_VOICES = 24
        const val OPP_PAN = -0.8
        const val CEILING_DB = -1.0
        private const val KNEE = 0.7

        /** Peak level in dBFS for each cue when played at the centre (the loudness ladder of GAME-TELL.md). */
        val LADDER: Map<Cue, Double> = mapOf(
            Cue.TAP to -17.0, Cue.DETENT_1 to -9.0, Cue.DETENT_2 to -9.0, Cue.STAMP to -17.0,
            Cue.SNARE_CLOSE to -14.0, Cue.KICK_ROLL to -17.0,
            Cue.JAB to -12.0, Cue.GUARD to -12.0, Cue.CHARGE to -12.0,
            Cue.PARRY_CLANG to -9.0, Cue.COIN to -9.0, Cue.FIZZLE to -9.0, Cue.CRACK_BOOM to -9.0, Cue.NOTHING to -9.0,
            Cue.HIT to -9.0,
            Cue.WIN to -8.0, Cue.LOSS to -8.0, Cue.DRAW to -8.0,
            Cue.FORFEIT to -20.0,
        )
        const val STATUS_DB = -22.0
        const val LIFE_DB = -22.0
        const val BED_DB = -20.0
        const val HOLD_HUM_DB = -24.0
        const val HOLD_RISER_DB = -30.0
        private fun lin(db: Double) = 10.0.pow(db / 20.0).toFloat()
    }

    private class Voice(
        val src: FloatArray, val loop: Boolean, val gNear: Float, val gFar: Float, val nearLeft: Boolean,
        val delay: Int, val farA: Float, var wait: Int, val attack: Int,
    ) {
        var pos = 0
        var age = 0
        var z = 0f
        var relTotal = 0
        var relLeft = -1          // -1 = not releasing
        val length get() = src.size + delay
        fun at(j: Int): Float {
            val n = src.size
            return if (loop) src[Math.floorMod(j, n)] else if (j < 0 || j >= n) 0f else src[j]
        }
        fun finished() = (!loop && pos >= length) || relLeft == 0
    }

    private val voices = ArrayList<Voice>()
    private var frames = 0L
    private var hum: Voice? = null
    private var riser: Voice? = null
    private var holdLevel = 0

    // bed state
    private var bedOn = false
    private var bedPos = 0
    private var bedGain = 0f
    private var bedFadeLeft = -1       // frames left in the stop ramp, -1 = none
    private var tension = 0
    private var g1 = 0f
    private var g2 = 0f
    private val bed = TellSynth.bed()

    init {
        require(sampleRate == TellSynth.SR) { "TellMixer supports 44100 Hz only" }
        TellSynth.warm()
    }

    fun framesRendered(): Long = frames
    fun activeVoices(): Int = voices.size

    // ---- the calls ----
    fun play(cue: Cue, side: Side = Side.ME) {
        add(TellSynth.cue(cue), LADDER.getValue(cue), side)
    }

    /** The two status notes of beat 1: yours first (centre, bell), theirs 100 ms later (left, muted pluck). */
    fun status(myEnergy: Int, oppEnergy: Int) {
        add(TellSynth.statusNote(myEnergy, true), STATUS_DB, Side.ME)
        add(TellSynth.statusNote(oppEnergy, false), STATUS_DB, Side.OPP, wait = sampleRate / 10)
    }

    /** [life] short descending notes (1..4) on the given side; nothing for 0. */
    fun lifeNotes(side: Side, life: Int) {
        if (life <= 0) return
        add(TellSynth.lifeNotes(life, side == Side.ME), LIFE_DB, side)
    }

    /** 0 = none, 1 = guard hum, 2 = octave up plus a faint riser. */
    fun setHold(level: Int) {
        val l = level.coerceIn(0, 2)
        if (l == holdLevel) return
        hum?.let { release(it, sampleRate / 100) }
        hum = null
        riser?.let { release(it, sampleRate / 100) }
        riser = null
        if (l > 0) {
            hum = add(TellSynth.holdHum(l), HOLD_HUM_DB, Side.ME, loop = true, attack = sampleRate / 100)
            if (l == 2) riser = add(TellSynth.holdRiser(), HOLD_RISER_DB, Side.ME)
        }
        holdLevel = l
    }

    /** The first kick falls on the next rendered frame; then every 22050 frames. */
    fun startBed() {
        bedOn = true; bedPos = 0; bedGain = 1f; bedFadeLeft = -1
        g1 = if (tension >= 1) 1f else 0f; g2 = if (tension >= 2) 1f else 0f
    }

    fun setTension(level: Int) { tension = level.coerceIn(0, 2) }

    /** End of the music. tapeStop: the bed falls in pitch for 400 ms. Otherwise it stops and a held C2 rings. */
    fun stopBed(tapeStop: Boolean) {
        if (!bedOn) return
        if (tapeStop) {
            val n = sampleRate * 400 / 1000; val out = FloatArray(n); var acc = 0.0; val top = lin(BED_DB)
            for (i in 0 until n) {
                val u = i.toDouble() / n; val rate = (1 - u).pow(1.3)
                val p = bedPos + acc; val i0 = p.toInt(); val fr = (p - i0).toFloat()
                val a = bedMix(i0 % TellSynth.ROUND_FRAMES); val b = bedMix((i0 + 1) % TellSynth.ROUND_FRAMES)
                var s = (a + (b - a) * fr) * top * (1 - u).pow(.5).toFloat()
                val r = n - 1 - i; if (r < 176) s *= r / 176f
                out[i] = s; acc += rate
            }
            val v = Voice(out, false, 1f, 1f, true, 0, 1f, 0, 0)
            addVoice(v)
            bedOn = false; bedFadeLeft = -1
        } else {
            bedFadeLeft = sampleRate * 4 / 1000
            add(TellSynth.bassHold(), BED_DB, Side.ME)
        }
    }

    /** The kick alone (tension 0) is lifted to peak 1.0 so the bed keeps its -20 dBFS peak; the lift fades out as layers enter. */
    private val kickLift = 1f / bed.kick.maxOf { abs(it) }

    /** Kick always; bass (and its bright copy) from tension 1; hi-hat and the high ostinato from tension 2. */
    private fun bedMix(i: Int) = bed.kick[i] * (1f + (kickLift - 1f) * (1f - g1)) + (bed.bass[i] + bed.bright[i]) * g1 + (bed.hat[i] + bed.ostinato[i]) * g2

    // ---- internals ----
    private fun add(src: FloatArray, db: Double, side: Side, wait: Int = 0, loop: Boolean = false, attack: Int = 0): Voice {
        val pan = if (side == Side.ME) 0.0 else OPP_PAN
        val ang = (pan + 1) * PI / 4
        val level = lin(db) * (if (side == Side.OPP) 0.5f else 1f)       // OPP: the 6 dB is in the equal-power split below
        // equal power, centre = unity per ear (x sqrt2); OPP total power is a quarter (-6 dB) of ME's
        val near = (sqrt(2.0) * cos(ang)).toFloat()
        val far = (sqrt(2.0) * sin(ang)).toFloat()
        val amount = minOf(1.0, abs(pan) / 0.8)
        val delay = (0.0006 * sampleRate * amount).roundToInt()
        val fc = if (amount <= 0.0) 0.0 else 16000.0 * (3000.0 / 16000.0).pow(amount)
        val farA = if (fc <= 0.0) 1f else (1.0 - exp(-2 * PI * fc / sampleRate)).toFloat()
        var s = src
        if (side == Side.OPP) {   // 5 kHz low-pass on the source: it feels across the room
            val a = (1.0 - exp(-2 * PI * 5000.0 / sampleRate)).toFloat(); var y = 0f
            s = FloatArray(src.size) { y += a * (src[it] - y); y }
        }
        return addVoice(Voice(s, loop, near * level, far * level, pan <= 0.0, delay, farA, wait, attack))
    }

    private fun addVoice(v: Voice): Voice {
        if (voices.size >= MAX_VOICES) {
            // steal the voice with the least sound left (never the hum)
            var best = -1; var left = Int.MAX_VALUE
            for ((i, o) in voices.withIndex()) {
                if (o === hum) continue
                val l = if (o.loop) Int.MAX_VALUE - 1 else o.length - o.pos
                if (l < left) { left = l; best = i }
            }
            if (best >= 0) voices.removeAt(best)
        }
        voices.add(v)
        return v
    }

    private fun release(v: Voice, frames: Int) { v.relTotal = frames; v.relLeft = frames }

    /** Mix [frames] frames into out (interleaved stereo, out.size >= 2*frames). Overwrites. */
    fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        for (v in voices) mixVoice(v, out, frames)
        voices.removeAll { it.finished() }
        if (hum != null && hum!!.finished()) hum = null
        if (bedOn) mixBed(out, frames)
        val ceil = lin(CEILING_DB); val room = ceil - KNEE.toFloat()
        for (i in 0 until frames * 2) {
            val x = out[i]; val a = abs(x)
            if (a > KNEE) out[i] = Math.copySign(KNEE.toFloat() + room * tanh(((a - KNEE) / room)).toFloat(), x)
        }
        this.frames += frames
    }

    private fun mixVoice(v: Voice, out: FloatArray, n: Int) {
        for (i in 0 until n) {
            if (v.wait > 0) { v.wait--; continue }
            if (v.finished()) break
            val near = v.at(v.pos)
            var far = v.at(v.pos - v.delay)
            if (v.farA < 1f) { v.z += v.farA * (far - v.z); far = v.z }
            var e = 1f
            if (v.age < v.attack) e = (v.age + 1).toFloat() / v.attack
            if (v.relLeft >= 0) { e *= v.relLeft.toFloat() / v.relTotal; v.relLeft-- }
            val l = if (v.nearLeft) near * v.gNear else far * v.gFar
            val r = if (v.nearLeft) far * v.gFar else near * v.gNear
            out[2 * i] += l * e; out[2 * i + 1] += r * e
            v.pos++; v.age++
        }
    }

    private fun mixBed(out: FloatArray, n: Int) {
        val top = lin(BED_DB); val k = (1.0 - exp(-1.0 / (sampleRate * 0.15))).toFloat()
        val t1 = if (tension >= 1) 1f else 0f; val t2 = if (tension >= 2) 1f else 0f
        for (i in 0 until n) {
            if (!bedOn) break
            g1 += (t1 - g1) * k; g2 += (t2 - g2) * k
            var gain = bedGain
            if (bedFadeLeft >= 0) { gain *= bedFadeLeft / (sampleRate * 4 / 1000f); bedFadeLeft-- ; if (bedFadeLeft < 0) { bedOn = false; break } }
            val s = bedMix(bedPos) * top * gain
            out[2 * i] += s; out[2 * i + 1] += s
            bedPos = if (bedPos + 1 == TellSynth.ROUND_FRAMES) 0 else bedPos + 1
        }
    }
}
