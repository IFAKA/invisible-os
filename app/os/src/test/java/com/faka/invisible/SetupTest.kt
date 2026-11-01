package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {
    private val none = SetupState(false, false, false, false, false, false, false, false, 34)
    private val all = SetupState(true, true, true, true, true, true, true, true, 34, contacts = true, calls = true, sms = true, callLog = true)

    @Test fun firstStepIsButtonAccess() {
        val st = Setup.steps(none)
        assertEquals("button", st[Setup.current(st)].id)
        assertEquals("Step 1 of 3", Setup.label(0, Setup.firstRun(st).size))
    }
    @Test fun newUserIsAskedForButtonThenTestThenNotificationsAndNothingElse() {
        assertEquals(listOf("button", "test", "notif"), Setup.firstRun(Setup.steps(none)).map { it.id })
        val st = Setup.firstRun(Setup.steps(none.copy(button = true, buttonTested = true)))
        assertEquals("notif", st[Setup.current(st)].id)
        assertTrue(st.last().optional)   // skippable, never a wall
        val skipped = Setup.firstRun(Setup.steps(none.copy(button = true, buttonTested = true, skipped = setOf("notif"))))
        assertEquals(-1, Setup.current(skipped))
        assertTrue(Setup.ready(Setup.steps(none.copy(button = true, buttonTested = true))))
    }
    @Test fun optionalStepsComeAfterOneAtATimeInOrder() {
        val st = Setup.steps(all.copy(assistant = false, postNotifications = false, calendar = false, location = false, usage = false))
        val seen = ArrayList<String>()
        var skipped = emptySet<String>()
        while (true) {
            val n = Setup.later(Setup.steps(all.copy(assistant = false, postNotifications = false, calendar = false, location = false, usage = false, skipped = skipped)), true) ?: break
            seen += n.id; skipped = skipped + n.id
        }
        assertEquals(listOf("assist", "post", "calendar", "location", "usage"), seen)
        assertEquals(null, Setup.later(st, practiceDone = false))
    }
    @Test fun comingBackRepeatsTapsOnceThenOnlyHints() {
        assertEquals(Setup.Back.DONE, Setup.afterReturn(true, 0))
        assertEquals(Setup.Back.REPEAT_TAPS, Setup.afterReturn(false, 0))
        assertEquals(Setup.Back.HINT_ONLY, Setup.afterReturn(false, 1))
    }
    @Test fun everyTapSentenceIsShortAndEveryStepHasTaps() {
        for (id in Setup.steps(all).map { it.id } + "test") {
            val taps = com.faka.invisible.voicekit.Voice.setupTaps(id)
            assertTrue(id, taps.isNotEmpty())
            taps.forEach { assertTrue(it.text, it.text.split(" ").size <= 7) }
        }
    }
    @Test fun assistantIsOfferedOnlyAfterPracticeThenUsageAndSkipIsFinal() {
        val st = Setup.steps(all.copy(assistant = false, usage = false))
        assertEquals(null, Setup.later(st, practiceDone = false))
        assertEquals("assist", Setup.later(st, practiceDone = true)?.id)
        val skipped = Setup.steps(all.copy(assistant = false, usage = false, skipped = setOf("assist")))
        assertEquals("usage", Setup.later(skipped, practiceDone = true)?.id)
        assertEquals(null, Setup.later(Setup.steps(all), practiceDone = true))
    }
    @Test fun needCardOnlyWhileTheFeatureIsStillMissing() {
        assertEquals("calendar", Needs.pending("calendar") { true }?.id)
        assertEquals(null, Needs.pending("calendar") { false })
        assertEquals(null, Needs.pending("nope") { true })
        assertEquals(null, Needs.pending(null) { true })
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
        assertFalse(Setup.ready(Setup.steps(all.copy(buttonTested = false))))
    }
    @Test fun callHistoryIsAnOptionalStepWithItsReason() {
        val st = Setup.steps(all.copy(callLog = false))
        val step = st[Setup.current(st)]
        assertEquals("calllog", step.id); assertTrue(step.optional)
        assertEquals("So your most-called people come first.", step.reason)
        assertTrue(Setup.ready(st))
    }
    @Test fun buttonAccessIsLostOnlyWhenItWasOnBefore() {
        assertTrue(ButtonAccess.lost(wasOn = true, nowOn = false))
        assertFalse(ButtonAccess.lost(wasOn = false, nowOn = false))
        assertFalse(ButtonAccess.lost(wasOn = true, nowOn = true))
    }
}
