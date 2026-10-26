package com.faka.invisible.counter

import com.faka.invisible.oskit.testing.FakeProgramHost
import com.faka.invisible.voicekit.Pacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterProgramTest {
    private fun started(): Pair<CounterProgram, FakeProgramHost> {
        val host = FakeProgramHost(); val p = CounterProgram()
        p.start(host); host.takeSpoken()
        return p to host
    }

    @Test fun welcomeTellsHowToLeave() {
        val host = FakeProgramHost(); val p = CounterProgram()
        p.start(host)
        assertEquals(listOf("Counter. Click to add one.", "Two clicks: start again.", "Hold three seconds to leave."), host.spoken)
        assertTrue(p.active)
    }

    @Test fun everyClickSpeaksTheCount() {
        val (p, host) = started()
        repeat(3) { host.click(p); host.advance(Pacing.GAP_MS + 10) }
        assertEquals(listOf("1", "2", "3"), host.spoken)
    }

    @Test fun twoClicksStartAgain() {
        val (p, host) = started()
        host.click(p); host.advance(Pacing.GAP_MS + 10)
        host.click(p); host.advance(100); host.click(p); host.advance(Pacing.GAP_MS + 10)
        assertEquals(listOf("1", "Zero."), host.spoken)
    }

    @Test fun holdingThreeSecondsLeavesOnce() {
        val (p, host) = started()
        host.hold(p, Pacing.LEAVE_MS + 100)
        assertEquals(1, host.endedCount)
        assertFalse(p.active)
        p.stop()
        assertEquals(1, host.endedCount)   // ended() is called once only
    }

    @Test fun aShortHoldIsJustAClick() {
        val (p, host) = started()
        host.hold(p, 1500); host.advance(Pacing.GAP_MS + 10)
        assertEquals(0, host.endedCount)
        assertEquals(listOf("1"), host.spoken)
    }
}
