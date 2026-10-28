package com.faka.invisible.breathe

import com.faka.invisible.oskit.testing.FakeProgramHost
import com.faka.invisible.voicekit.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreatheProgramTest {
    private val h = FakeProgramHost()
    private val played = ArrayList<Int>()
    private val vol = object : MusicVolume { var v = 10; val log = ArrayList<Int>(); override fun get() = v; override fun set(v: Int) { this.v = v; log.add(v) } }
    private fun make() = BreatheProgram({ vol }, { ShortArray(it.seconds) }, { played.add(it.size) }, { }, { it() })
    private val p = make()

    private fun clicks(n: Int) { repeat(n) { h.click(p); h.advance(100) }; h.advance(500) }

    @Test fun rules() {
        assertEquals(14_000L, BreatheCore.roundMs()); assertEquals(6, BreatheCore.addRound(5)); assertEquals(30, BreatheCore.addRound(30))
        assertEquals(6000L, BreatheCore.fadeStepMs(10))
    }

    @Test fun fiveRoundsOfInHoldOut() {
        p.start(h); h.takeSpoken(); clicks(1)
        h.advance(3000)   // the intro
        h.advance(5 * 14_000L - 1)
        assertEquals(List(5) { listOf(4, 4, 6) }.flatten(), played)
        h.advance(10_000)
        assertTrue(h.spoken.contains("Well done.")); assertEquals(1, h.endedCount)
    }

    @Test fun clickAddsRound() {
        p.start(h); clicks(1); clicks(1)   // start, then add one
        h.advance(3000 + 6 * 14_000L + 3000)
        assertEquals(18, played.size)
    }

    @Test fun sleepFadesThenPauses() {
        p.start(h); h.takeSpoken(); clicks(2)
        assertEquals(listOf("Sleep timer. The music fades in one minute."), h.takeSpoken())
        h.advance(59_000)
        assertFalse(h.musicPaused); assertTrue(vol.v < 10 && vol.v > 0)
        h.advance(2_000)
        assertEquals(10, vol.v)   // back to the start volume
        assertEquals(0, h.endedCount.coerceAtMost(0))
        h.advance(3000)
        assertEquals(1, h.endedCount)
    }

    @Test fun leavingDuringSleepRestoresVolume() {
        p.start(h); clicks(2); h.advance(20_000)
        assertTrue(vol.v < 10)
        clicks(3)
        assertEquals(10, vol.v); assertEquals(1, h.endedCount)
    }

    @Test fun clickCancelsSleep() {
        p.start(h); clicks(2); h.advance(10_000); clicks(1)
        assertEquals(10, vol.v); h.advance(120_000); assertEquals(10, vol.v)
    }
}
