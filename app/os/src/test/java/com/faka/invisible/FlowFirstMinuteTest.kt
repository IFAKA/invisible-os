package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

/** The first minute and the end of a session, on the laptop (fake time, real button timing): value first, then a short "Done", then one practice offer. */
class FlowFirstMinuteTest {
    private fun flow(done: Int = 0, offered: Boolean = false) = Flow().also { it.r.doneCount = done; it.r.offered = offered }

    @Test fun first_wake_opens_home_with_the_hint_not_the_practice() {
        val f = flow(); f.r.codes = 0   // a new person
        f.press("900")
        f.need("Click when you hear it")   // the short hint: what a click does, back, up, how to close
        assertTrue(f.active()); assertEquals(ControllerMode.Screen.HUB, f.screen())
        assertFalse("the practice must not be forced", f.said("practice"))
    }

    @Test fun a_session_with_a_pick_ends_with_done_then_the_practice_offer_once() {
        val f = flow(); f.m.devShow("PHONE"); f.heardStart("Emergency")
        f.press("120"); f.advance(400)                       // a real pick
        val i = f.mark()
        f.press("120,150,900"); f.advance(400)               // click then hold: close
        assertFalse(f.active())
        f.need("Done. Hold again any time.", i)
        f.need("Want to practice? Click for yes.", i)
        assertEquals(1, f.r.doneCount); assertTrue(f.r.offered); assertEquals(1, f.r.offersMade.size)
    }

    @Test fun closing_without_a_pick_says_no_done_line() {
        val f = flow(offered = true); f.m.devShow("PHONE"); f.heardStart("Emergency")
        val i = f.mark()
        f.press("120,150,900"); f.advance(3000)
        assertFalse(f.active()); assertFalse(f.said("Done. Hold again", i)); assertEquals(0, f.r.doneCount)
    }

    @Test fun the_done_line_is_said_only_the_first_three_sessions() {
        val f = flow(done = 3, offered = true); f.m.devShow("PHONE"); f.heardStart("Emergency")
        f.press("120"); f.advance(400)
        val i = f.mark()
        f.press("120,150,900"); f.advance(3000)
        assertFalse(f.said("Done. Hold again", i)); assertEquals(3, f.r.doneCount)
    }

    @Test fun the_practice_is_offered_only_once() {
        val f = flow(done = 3, offered = true); f.m.devShow("PHONE"); f.heardStart("Emergency")
        val i = f.mark()
        f.press("120,150,900"); f.advance(3000)
        assertFalse(f.said("Want to practice", i)); assertEquals(0, f.r.offersMade.size)
    }
}
