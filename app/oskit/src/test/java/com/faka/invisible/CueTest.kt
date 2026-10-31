package com.faka.invisible

import com.faka.invisible.voicekit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The Cue stream is the one model: folding cues gives the picture `Kit` draws. */
class CueTest {
    @Before fun setUp() { ScreenMirror.clear(); ScreenMirror.onChange = null; ScreenMirror.now = { 7L } }
    @After fun tearDown() { ScreenMirror.clear() }

    @Test fun listCueMarksTheItem() {
        val s = ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.LIST_ITEM, "b", index = 1, list = listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), s.items); assertEquals(1, s.index); assertTrue(s.active)
    }

    @Test fun linesKeepTheLastFourNewestMarked() {
        var s = ScreenMirror.Snapshot()
        for (t in listOf("1", "2", "3", "4", "5")) s = ScreenMirror.reduce(s, Cue(Cue.Kind.LINE, t))
        assertEquals(listOf("2", "3", "4", "5"), s.items); assertEquals(3, s.index)
        assertEquals(s, ScreenMirror.reduce(s, Cue(Cue.Kind.LINE, " ")))
    }

    @Test fun pressUsesOnsetOrClock() {
        assertEquals(99L, ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.PRESS, "click", onsetMs = 99)).pressAt)
        assertEquals(7L, ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.PRESS, "click"), { 7L }).pressAt)
    }

    @Test fun loopCuesDrawRoundItemAndChoice() {
        ScreenMirror.loopHeader(listOf("Letters: -"))
        val ring = listOf(LoopShown("a", "a", false), LoopShown("b", "b", false))
        Cues.emit(Cue(Cue.Kind.LOOP_ITEM, ring = ring))
        assertEquals(-1, ScreenMirror.state.loop!!.at); assertEquals(2, ScreenMirror.state.loop!!.ring.size)
        Cues.emit(Cue(Cue.Kind.LOOP_ITEM, "b", index = 1, item = ring[1]))
        assertEquals(1, ScreenMirror.state.loop!!.at)
        Cues.emit(Cue(Cue.Kind.LOOP_ITEM, "b", index = 1, chosen = true, item = ring[1]))
        assertEquals("b", ScreenMirror.state.loop!!.chosen!!.text)
        Cues.emit(Cue(Cue.Kind.LOOP_ITEM))
        assertEquals(-1, ScreenMirror.state.loop!!.at); assertNotNull(ScreenMirror.state.loop!!.chosen)
    }

    @Test fun loopCuesWithoutALoopScreenAreIgnored() {
        Cues.emit(Cue(Cue.Kind.LOOP_ITEM, "x", index = 0))
        assertNull(ScreenMirror.state.loop)
    }

    @Test fun soundCueChangesNothingYet() {
        val before = ScreenMirror.state; Cues.sound("tick"); assertEquals(before, ScreenMirror.state)
    }
}
