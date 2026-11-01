package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class HomeStatusTest {
    @Test fun allGrantedSaysWorkingAndHasNoFix() {
        val l = HomeStatus.line(running = true, buttonAccess = true, notifications = true)
        assertEquals("Working. Hold your earbud button.", l.text); assertNull(l.fixId); assertFalse(l.problem)
    }
    @Test fun buttonAccessOffIsTheFirstProblem() {
        val l = HomeStatus.line(running = false, buttonAccess = false, notifications = false)
        assertEquals("Button access is off.", l.text); assertEquals("Turn on", l.fixLabel); assertEquals("button", l.fixId); assertTrue(l.problem)
    }
    @Test fun serviceOffIsOneFixWithOn() {
        val l = HomeStatus.line(running = false, buttonAccess = true, notifications = true)
        assertEquals("Invisible OS is off.", l.text); assertEquals("on", l.fixId)
    }
    @Test fun notificationAccessOff() {
        val l = HomeStatus.line(running = true, buttonAccess = true, notifications = false)
        assertEquals("Notification access is off.", l.text); assertEquals("notif", l.fixId)
    }
    @Test fun aFeatureNeedComesLastAndOnlyOneThingIsShown() {
        val l = HomeStatus.line(true, true, true, Triple("Calls need phone access.", "Allow", "calls"))
        assertEquals("Calls need phone access.", l.text); assertEquals("calls", l.fixId)
        assertEquals("button", HomeStatus.line(true, false, false, Triple("x", "y", "z")).fixId)
    }
    @Test fun everySentenceIsAtMostSevenWords() {
        listOf(HomeStatus.WORKING, "Button access is off.", "Invisible OS is off.", "Notification access is off.").forEach { assertTrue(it, it.split(" ").size <= 7) }
    }
}
