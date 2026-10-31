package com.faka.invisible

import com.faka.invisible.voicekit.Cue
import com.faka.invisible.voicekit.CueGate
import org.junit.Assert.assertEquals
import org.junit.Test

class CueGateTest {
    private val seen = ArrayList<Cue>()
    private val gate = CueGate { seen += it }
    private fun line(t: String) = Cue(Cue.Kind.LINE, t)

    @Test fun notVisibleBeforeOnset() {
        gate.hold(line("a"), false)
        assertEquals(0, seen.size); assertEquals(1, gate.pending)
    }

    @Test fun visibleAtOnsetWithRealTime() {
        gate.hold(line("a"), false); gate.started(1234)
        assertEquals(listOf("a"), seen.map { it.text }); assertEquals(1234L, seen[0].onsetMs); assertEquals(0, gate.pending)
    }

    @Test fun droppedWhenReplacedOrCancelled() {
        gate.hold(line("a"), false); gate.hold(line("b"), false); gate.started(5)
        assertEquals(listOf("b"), seen.map { it.text })
        gate.hold(line("c"), false); gate.cancel(); gate.started(9)
        assertEquals(1, seen.size)
    }

    @Test fun queuedLinesWaitBehindAndShowInOrder() {
        gate.hold(line("a"), false); gate.hold(line("b"), true); gate.started(7)
        assertEquals(listOf("a", "b"), seen.map { it.text })
    }

    @Test fun emittedOnErrorSoTheScreenNeverFreezes() {
        gate.hold(line("a"), false); gate.fail(42)
        assertEquals(listOf("a"), seen.map { it.text }); assertEquals(42L, seen[0].onsetMs)
    }
}
