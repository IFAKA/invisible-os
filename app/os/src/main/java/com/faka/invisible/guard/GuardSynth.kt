package com.faka.invisible.guard

import com.faka.invisible.echo.EchoSynth
import com.faka.invisible.echo.EchoSynth.then
import com.faka.invisible.echo.EchoSynth.tone
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** The sounds of GUARD, built once (pure maths, cheap: all under half a second). One sound always means one thing. */
object GuardSynth {
    const val SR = EchoSynth.SR

    private fun mix(vararg parts: Pair<FloatArray, Int>): FloatArray {
        val n = parts.maxOf { SR * it.second / 1000 + it.first.size }
        val out = FloatArray(n)
        for ((s, gapMs) in parts) { val off = SR * gapMs / 1000; for (i in s.indices) out[off + i] += s[i] }
        return out
    }

    /** Soft noise with a hump-shaped envelope, low-passed: the air whoosh. */
    private fun noise(ms: Int, lowpass: Double, hump: Boolean, decayMs: Double = 60.0): FloatArray {
        val n = SR * ms / 1000
        val rng = Random(7)
        var y = 0.0
        return FloatArray(n) { i ->
            y += lowpass * (rng.nextDouble() * 2 - 1 - y)
            val t = i.toDouble() / n
            val env = if (hump) sin(PI * t) else kotlin.math.exp(-i.toDouble() / SR * 1000.0 / decayMs)
            (y * env * 2.2).toFloat()
        }
    }

    // enemies: announce (beat 0) and steps (beats 1 to 3); the same enemy always sounds the same
    val announceThief by lazy { then(tone(300.0, 90, 35.0), tone(400.0, 120, 45.0), 80) }
    val announceKnight by lazy { tone(720.0, 200, 80.0, 0.9) }
    val announceOgre by lazy { tone(62.0, 420, 170.0, 0.3) }
    val bell by lazy { tone(1568.0, 600, 260.0, 0.4) }
    val stepThief by lazy { tone(210.0, 70, 18.0, 0.3) }
    val stepKnight by lazy { tone(940.0, 90, 28.0, 0.9) }
    val stepOgre by lazy { tone(75.0, 280, 100.0, 0.2) }
    /** The ogre's real thud (beat 4) and its light off-beat fake-out. */
    val ogreStrike by lazy { mix(tone(52.0, 380, 150.0, 0.5) to 0, tone(1400.0, 30, 8.0) to 0) }
    val fake by lazy { tone(880.0, 50, 18.0, 0.2) }
    /** Practice marker (a soft double pip before the enemy) and the ping that marks a strike beat while you learn. */
    val practice by lazy { then(tone(1250.0, 50, 20.0), tone(1250.0, 50, 20.0), 110) }
    val ping by lazy { tone(2400.0, 140, 55.0, 0.2) }
    // your answers
    val perfect by lazy { mix(tone(1760.0, 420, 150.0, 1.0) to 0, tone(2093.0, 70, 30.0) to 60, tone(2637.0, 70, 30.0) to 110, tone(3136.0, 140, 60.0) to 160) }
    val good by lazy { tone(740.0, 260, 90.0, 0.5) }
    val whoosh by lazy { noise(200, 0.12, true) }
    val thud by lazy { mix(tone(68.0, 400, 150.0, 0.1) to 0, noise(60, 0.25, false, 18.0) to 0) }
    val calm by lazy { then(tone(784.0, 150, 70.0), tone(1175.0, 280, 120.0), 100) }
    val heal by lazy { then(then(tone(523.0, 140, 60.0), tone(659.0, 140, 60.0), 100), tone(988.0, 320, 140.0, 0.4), 100) }
    val heartbeat by lazy { then(tone(58.0, 90, 45.0), tone(48.0, 120, 55.0), 170) }
    val over by lazy { then(then(tone(330.0, 200, 90.0), tone(247.0, 200, 90.0), 160), tone(165.0, 600, 260.0), 160) }
    // calibration
    val tick by lazy { tone(1500.0, 40, 10.0) }
    val tap by lazy { tone(1000.0, 40, 12.0) }

    fun announce(k: Kind) = when (k) { Kind.THIEF, Kind.FAST_THIEF -> announceThief; Kind.KNIGHT -> announceKnight; Kind.OGRE -> announceOgre; Kind.DECOY -> bell }
    fun step(k: Kind) = when (k) { Kind.THIEF, Kind.FAST_THIEF -> stepThief; Kind.KNIGHT -> stepKnight; Kind.OGRE -> stepOgre; Kind.DECOY -> stepThief }

    fun warm() {
        announceThief; announceKnight; announceOgre; bell; stepThief; stepKnight; stepOgre; ogreStrike; fake; practice; ping
        perfect; good; whoosh; thud; calm; heal; heartbeat; over; tick; tap
    }
}
