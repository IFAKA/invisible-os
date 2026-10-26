package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {
    private val none = SetupState(false, false, false, false, false, false, false, false, 34)
    private val all = SetupState(true, true, true, true, true, true, true, true, 34)

    @Test fun firstStepIsButtonAccess() {
        val st = Setup.steps(none)
        assertEquals("button", st[Setup.current(st)].id)
        assertEquals("Step 1 of 8", Setup.label(0, st.size))
    }
    @Test fun testStepWaitsForButtonAccess() {
        val st = Setup.steps(none.copy(button = true))
        assertEquals("test", st[Setup.current(st)].id)
    }
    @Test fun everythingOnMeansAllSet() {
        val st = Setup.steps(all)
        assertEquals(-1, Setup.current(st)); assertTrue(Setup.ready(st))
    }
    @Test fun oldAndroidHasNoNotificationPermissionStep() {
        assertFalse(Setup.steps(none.copy(sdk = 31)).any { it.id == "post" })
        assertTrue(Setup.steps(none).any { it.id == "post" })
    }
    @Test fun skippingOptionalMovesOnButRequiredCannotBeSkipped() {
        val s = all.copy(usage = false, skipped = setOf("usage", "button"))
        val st = Setup.steps(s)
        assertEquals(-1, Setup.current(st))
        val s2 = Setup.steps(none.copy(skipped = setOf("button")))
        assertEquals("button", s2[Setup.current(s2)].id)
    }
    @Test fun readyIgnoresOptionalSteps() {
        assertTrue(Setup.ready(Setup.steps(all.copy(usage = false, location = false))))
        assertFalse(Setup.ready(Setup.steps(all.copy(assistant = false))))
    }
}
