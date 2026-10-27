package com.faka.invisible

import com.faka.invisible.practice.PracticeProgram
import com.faka.invisible.voicekit.Pacing
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The button path with the real [com.faka.invisible.voicekit.ButtonInput] on a real (paused) Android looper: programs that own the raw button. See the table in FlowTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowButtonTest {
    // ---- practice_end_to_end ----
    @Test fun practice_end_to_end() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram()
        rig.running = p; p.start(host)
        rig.need("click once now")
        rig.press("120");                 rig.need("click twice")
        rig.press("120,150,120");         rig.need("click three times")
        rig.press("100,150,100,150,100"); rig.need("click, then hold")
        assertEquals(0, host.ended)
        rig.press("120,150,900")          // click, then hold: the OS (ButtonInput) catches it and closes the practice
        rig.advance(Pacing.GAP_MS)
        assertEquals("the practice did not end", 1, host.ended)
        assertEquals(1, rig.exits)
        assertTrue(host.prefs.getBoolean(PracticeProgram.DONE_KEY, false))   // it ran once: never again by itself
    }

    // ---- click then hold leaves any program (also the tail of screen_tap_picks) ----
    @Test fun click_then_hold_leaves_a_program_but_a_plain_hold_does_not() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram()
        rig.running = p; p.start(host)
        rig.press("900")   // a plain hold (under 3 s) is not an exit
        rig.advance(Pacing.GAP_MS)
        assertEquals(0, rig.exits); assertEquals(0, host.ended)
        rig.press("120,150,900")
        rig.advance(Pacing.GAP_MS)
        assertEquals(1, rig.exits); assertEquals(1, host.ended)
    }

    // ---- hold 3 s always leaves ----
    @Test fun hold_three_seconds_leaves() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram()
        rig.running = p; p.start(host)
        rig.press("3200"); rig.advance(Pacing.GAP_MS)
        assertEquals(1, host.ended)
    }
}
