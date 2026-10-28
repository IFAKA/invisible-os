package com.faka.invisible.counter

import com.faka.invisible.oskit.testing.FakeProgramHost
import com.faka.invisible.voicekit.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

class CounterProgramTest {
    private val h = FakeProgramHost()
    private val tones = ArrayList<Tone>()
    private val p = CounterProgram { tones.add(it) }

    private fun clicks(n: Int) { repeat(n) { h.click(p); h.advance(100) }; h.advance(500) }

    @Test fun rules() {
        assertEquals(listOf(false, true, false), listOf(CounterCore.spoken(4), CounterCore.spoken(5), CounterCore.spoken(0)))
        assertEquals(0, CounterCore.undo(0)); assertEquals("Reps", CounterCore.NAMES[CounterCore.next(2)])
    }

    @Test fun ticksThenSpeaksEveryFifth() {
        p.start(h); h.takeSpoken()
        repeat(5) { clicks(1) }
        assertEquals(listOf("5"), h.takeSpoken())
        assertEquals(List(4) { Tone.TICK }, tones)
        assertEquals(5, h.prefs.getInt("counter_Reps", 0))
    }

    @Test fun undo() {
        p.start(h); repeat(3) { clicks(1) }; h.takeSpoken()
        clicks(2)
        assertEquals(listOf("2"), h.takeSpoken())
        assertEquals(2, h.prefs.getInt("counter_Reps", 0))
    }

    @Test fun holdReadsTotalAndDoesNotAdd() {
        p.start(h); repeat(2) { clicks(1) }; h.takeSpoken()
        h.hold(p, 1200); h.advance(500)
        assertEquals(listOf("Reps, 2."), h.takeSpoken())
        assertEquals(2, h.prefs.getInt("counter_Reps", 0)); assertEquals(0, h.endedCount)
    }

    @Test fun fourClicksSwitchAndRemember() {
        p.start(h); clicks(1); h.takeSpoken()
        clicks(4)
        assertEquals(listOf("Laps, 0."), h.takeSpoken())
        clicks(1)
        assertEquals(1, h.prefs.getInt("counter_Laps", 0)); assertEquals(1, h.prefs.getInt("counter_Reps", 0))
        p.stop()
        val p2 = CounterProgram { }; val h2 = h; h2.takeSpoken(); p2.start(h2)
        assertEquals("Laps, 1.", h2.spoken.first())
    }

    @Test fun tripleLeaves() { p.start(h); clicks(3); assertEquals(1, h.endedCount) }
}
