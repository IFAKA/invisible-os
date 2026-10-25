package com.faka.invisible.tell

import com.faka.invisible.tell.TellSoundTools.centroidHz
import com.faka.invisible.tell.TellSoundTools.centsFromScale
import com.faka.invisible.tell.TellSoundTools.dominantHz
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class TellSynthTest {
    private val fade = TellSynth.SR * 4 / 1000

    @Test fun noNanAndPeakIsOne() {
        for (c in Cue.values()) {
            val x = TellSynth.cue(c)
            assertTrue("$c empty", x.isNotEmpty())
            assertTrue("$c nan", x.all { it.isFinite() })
            assertEquals("$c peak", 1.0, x.maxOf { abs(it) }.toDouble(), 1e-6)
            assertTrue("$c too long: ${x.size}", x.size <= TellSynth.SR * 1100 / 1000)
        }
    }

    @Test fun fadesAreFourMsAndNoJumps() {
        val report = StringBuilder()
        for (c in Cue.values()) {
            val x = TellSynth.cue(c); val n = x.size
            assertTrue("$c first sample ${x[0]}", abs(x[0]) < 0.01f)
            assertTrue("$c last sample ${x[n - 1]}", abs(x[n - 1]) < 0.01f)
            // inside the 4 ms fades the level follows the raised-cosine ramp (peak was 1 before the fade; renormalising can lift it 3x at most)
            for (i in 0 until minOf(fade, n / 2)) {
                val w = 0.5 - 0.5 * cos(PI * i / fade)
                assertTrue("$c fade-in $i", abs(x[i]) <= minOf(1.0, 3.0 * w + 0.002))
                assertTrue("$c fade-out $i", abs(x[n - 1 - i]) <= minOf(1.0, 3.0 * w + 0.002))
            }
            var jump = 0f; for (i in 1 until n) jump = maxOf(jump, abs(x[i] - x[i - 1]))
            report.append("%s=%.2f ".format(c, jump))
            assertTrue("$c max sample jump $jump", jump <= 0.75f)
        }
        println("TELL max sample jump per cue: $report")
    }

    private fun assertInScale(name: String, x: FloatArray, tolPct: Double = 1.5) {
        val f = dominantHz(x); val cents = centsFromScale(f)
        println("TELL pitch %-14s %8.2f Hz  %5.1f cents from scale".format(name, f, cents))
        assertTrue("$name dominant $f Hz is ${"%.1f".format(cents)} cents off the scale", cents <= 1200 * Math.log(1 + tolPct / 100) / Math.log(2.0))
    }

    @Test fun pitchedCuesAreInCMinorPentatonic() {
        for (c in listOf(Cue.TAP, Cue.DETENT_1, Cue.DETENT_2, Cue.STAMP, Cue.JAB, Cue.GUARD, Cue.PARRY_CLANG, Cue.COIN, Cue.HIT, Cue.WIN, Cue.LOSS, Cue.DRAW))
            assertInScale(c.name, TellSynth.cue(c))
        for (e in 0..5) { assertInScale("status_own_$e", TellSynth.statusNote(e, true)); assertInScale("status_opp_$e", TellSynth.statusNote(e, false)) }
        for (l in 1..4) { assertInScale("life_own_$l", TellSynth.lifeNotes(l, true)); assertInScale("life_opp_$l", TellSynth.lifeNotes(l, false)) }
        assertInScale("hum1", TellSynth.holdHum(1)); assertInScale("hum2", TellSynth.holdHum(2))
        assertInScale("bassHold", TellSynth.bassHold())
    }

    @Test fun statusPitchRisesWithEnergyAndMatchesTable() {
        var last = 0.0
        for (e in 0..5) for (own in listOf(true, false)) {
            val f = dominantHz(TellSynth.statusNote(e, own))
            val want = TellSynth.STATUS_HZ[e]
            assertEquals("energy $e own=$own", want, f, want * 0.015)
            if (own) { assertTrue("energy $e not above ${last}", f > last); last = f }
        }
    }

    @Test fun lifeNotesCountAndLength() {
        for (l in 1..4) assertEquals(0.070 * l, TellSynth.lifeNotes(l, true).size / 44100.0, 0.005)
    }

    @Test fun moveCentroidsDiffer() {
        val j = centroidHz(TellSynth.cue(Cue.JAB)); val g = centroidHz(TellSynth.cue(Cue.GUARD)); val c = centroidHz(TellSynth.cue(Cue.CHARGE))
        println("TELL centroids: JAB %.0f Hz, GUARD %.0f Hz, CHARGE %.0f Hz; ratios J/G %.2f C/J %.2f C/G %.2f".format(j, g, c, j / g, c / j, c / g))
        assertTrue("jab/guard", j / g >= 1.6); assertTrue("charge/jab", c / j >= 1.6); assertTrue("charge/guard", c / g >= 1.6)
    }

    @Test fun lengthsFollowTheSpec() {
        fun ms(c: Cue) = TellSynth.cue(c).size * 1000.0 / 44100
        assertEquals(20.0, ms(Cue.TAP), 1.0)
        assertEquals(110.0, ms(Cue.JAB), 1.0)
        assertEquals(220.0, ms(Cue.GUARD), 1.0)
        assertTrue(ms(Cue.CHARGE) >= 730)
        for (c in listOf(Cue.WIN, Cue.LOSS, Cue.DRAW)) assertEquals(600.0, ms(c), 1.0)
        assertEquals(150.0, TellSynth.statusNote(2, true).size * 1000.0 / 44100, 1.0)
    }

    @Test fun humLoopsSeamlessly() {
        for (l in 1..2) {
            val h = TellSynth.holdHum(l); assertEquals(TellSynth.HUM_FRAMES, h.size)
            var step = 0f; for (i in 1 until h.size) step = maxOf(step, abs(h[i] - h[i - 1]))
            val wrap = abs(h[0] - h[h.size - 1])
            assertTrue("hum $l wrap jump $wrap vs normal step $step", wrap <= step * 1.5f + 1e-4f)
        }
    }

    @Test fun bedLayersAreOneRound() {
        val b = TellSynth.bed()
        for (a in listOf(b.kick, b.hat, b.bass, b.bright, b.ostinato)) { assertEquals(TellSynth.ROUND_FRAMES, a.size); assertTrue(a.all { it.isFinite() }) }
        val peak = (0 until b.kick.size).maxOf { abs(b.kick[it] + b.hat[it] + b.bass[it]) }
        assertEquals(1.0, peak.toDouble(), 1e-4)
    }
}
