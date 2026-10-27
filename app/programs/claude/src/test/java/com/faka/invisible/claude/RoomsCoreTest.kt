package com.faka.invisible.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RoomsCoreTest {
    private fun room(vararg actions: String) = Room("%1", "os", "invisible-os", "claude", "needs", "say go", listOf("go"), actions.toList())

    @Test fun stateWords() {
        assertEquals("needs you", RoomState.word("needs")); assertEquals("failed", RoomState.word("failed")); assertEquals("done", RoomState.word("done"))
        assertEquals("working", RoomState.word("working")); assertEquals("idle", RoomState.word("idle")); assertEquals("idle", RoomState.word("???"))
    }
    @Test fun roomNameFallsBackToProject() {
        assertEquals("os", RoomState.nameOf(room()))
        assertEquals("invisible-os", RoomState.nameOf(room().copy(name = "  ")))
    }
    @Test fun roomLineIsNameThenStateWord() = assertEquals("os, needs you", ClaudeVoice.room("os", "needs").text)

    @Test fun labels() {
        assertEquals("Go", RoomText.label(RoomAction("go")))
        assertEquals("Explain simpler", RoomText.label(RoomAction("explain")))
        assertEquals("What do you recommend", RoomText.label(RoomAction("recommend")))
        assertEquals("Research first", RoomText.label(RoomAction("research")))
        assertEquals("Where are we", RoomText.label(RoomAction("progress")))
        assertEquals("Run again", RoomText.label(RoomAction("again")))
        assertEquals("Make test", RoomText.label(RoomAction("make:test")))
        assertEquals("keep 15", RoomText.label(RoomAction("option:keep 15")))
        assertEquals("Read all", RoomText.label(RoomAction("readall")))
    }
    @Test fun onlyPushAndClearAsk() {
        for (id in listOf("push", "clear")) assertTrue(id, RoomAction(id).needsConfirm)
        for (id in listOf("go", "yes", "commit", "compact", "stop", "hold", "again", "make:x", "option:go")) assertFalse(id, RoomAction(id).needsConfirm)
    }
    @Test fun phoneOnlyActionsAreNotSent() {
        for (id in listOf("readall", "screen", "write")) assertTrue(id, RoomAction(id).onPhone)
        assertFalse(RoomAction("go").onPhone)
    }
    @Test fun earbudListKeepsServerOrderAndDropsScreen() {
        val r = room("option:go", "go", "screen", "yes", "go", "readall", "write")
        assertEquals(listOf("option:go", "go", "yes", "readall", "write"), RoomAction.earbudList(r).map { it.id })
        assertEquals(listOf("option:go", "go", "yes"), RoomAction.screenList(r).map { it.id })
    }

    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test fun muteAtNightEndsNextMorning() {
        assertEquals(at(2026, 10, 28, 7), RoomMute.nextMorning(at(2026, 10, 27, 23, 30), tz))
    }
    @Test fun muteAfterMidnightEndsSameMorning() = assertEquals(at(2026, 10, 28, 7), RoomMute.nextMorning(at(2026, 10, 28, 1, 0), tz))
    @Test fun muteAtSevenSharpWaitsForTomorrow() = assertEquals(at(2026, 10, 29, 7), RoomMute.nextMorning(at(2026, 10, 28, 7, 0), tz))
    @Test fun muteAcrossClockChange() = assertEquals(at(2026, 10, 26, 7), RoomMute.nextMorning(at(2026, 10, 25, 12, 0), tz))   // 25 Oct 2026: clocks go back
    @Test fun mutedUntilTime() { assertTrue(RoomMute.muted(100, 99)); assertFalse(RoomMute.muted(100, 100)); assertFalse(RoomMute.muted(0, 5)) }
}
