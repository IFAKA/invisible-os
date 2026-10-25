package com.faka.invisible

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun hubKeepsKnownOnce() {
        assertEquals(listOf("timer"), LauncherSettings.cleanHub(listOf("timer", "bogus", "timer")))
    }
    @Test fun timersAreThreeAndBounded() {
        assertEquals(listOf(1, 10, 180), LauncherSettings.cleanTimers(listOf(0, 10, 999)))
        assertEquals(listOf(7, 10, 25), LauncherSettings.cleanTimers(listOf(7)))
    }
    @Test fun minutesWords() {
        assertEquals("One minute", Spoken.minutes(1)); assertEquals("Twenty five minutes", Spoken.minutes(25)); assertEquals("90 minutes", Spoken.minutes(90))
    }
}
