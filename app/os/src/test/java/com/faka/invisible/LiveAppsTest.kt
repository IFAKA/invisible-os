package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class LiveAppsTest {
    private val now = 1_000_000L
    private fun pick(vararg c: LiveCandidate, muted: Set<String> = emptySet()) = LiveApps.pick(c.toList(), now, "self", { it in muted })

    private val workout = LiveCandidate("run.app", "Workout", now - 1_000, notifKey = "k1", chronometer = true, hasActions = true, line = "12:30 running")
    private val music = LiveCandidate("music.app", "Music", now - 30_000, mediaPlaying = true, notifKey = "k2", hasActions = true, mediaStyle = true)
    private val usb = LiveCandidate("android", "Android System", now - 500, notifKey = "k3", line = "USB debugging connected")

    @Test fun workoutBeforeMusicAndStaticLeftOut() {
        assertEquals(listOf("run.app", "music.app"), pick(music, usb, workout).map { it.pkg })
    }
    @Test fun labelsAndKeys() {
        val l = pick(music, workout)
        assertEquals("playing", l[1].state); assertEquals("live", l[0].state)
        assertEquals("12:30 running", l[0].line); assertEquals("k1", l[0].notifKey)
    }
    @Test fun pausedMusicCountsOnlyForTenMinutes() {
        val recent = LiveCandidate("a", "A", now - 5 * 60_000, mediaPaused = true)
        val old = LiveCandidate("b", "B", now - 11 * 60_000, mediaPaused = true)
        assertEquals(listOf("a"), pick(recent, old).map { it.pkg })
        assertEquals("paused", pick(recent)[0].state)
    }
    @Test fun staticNotificationWithCategoryOrChronometerIsLive() {
        assertTrue(pick(LiveCandidate("n", "Nav", now, notifKey = "k", category = "navigation")).isNotEmpty())
        assertTrue(pick(LiveCandidate("t", "Stopwatch", now, notifKey = "k", chronometer = true)).isNotEmpty())
        assertTrue(pick(LiveCandidate("s", "Sys", now, notifKey = "k")).isEmpty())
    }
    @Test fun mutedAndSelfAreLeftOut() {
        assertTrue(pick(workout, muted = setOf("run.app")).isEmpty())
        assertTrue(pick(workout.copy(pkg = "self")).isEmpty())
    }
    @Test fun privateSaysNoLineAndMaxThree() {
        assertEquals("", pick(workout.copy(hidden = true))[0].line)
        val many = (1..5).map { LiveCandidate("p$it", "P$it", now - it, notifKey = "k$it", hasActions = true) }
        assertEquals(listOf("p1", "p2", "p3"), LiveApps.pick(many, now, "self", { false }).map { it.pkg })
    }
    @Test fun oneEntryPerApp() {
        val a = LiveCandidate("x", "X", now - 10, notifKey = "k", hasActions = true)
        val b = LiveCandidate("x", "X", now - 20, mediaPlaying = true)
        assertEquals(1, pick(a, b).size)
    }
    @Test fun longOrAppNameLinesAreDropped() {
        assertEquals("", LiveApps.shortLine("Workout", "Workout"))
        assertEquals("", LiveApps.shortLine("x".repeat(60), "W"))
    }
}
