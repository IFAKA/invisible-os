package com.faka.invisible

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureTest {
    private fun c(vararg p: Pair<Long, Long>) = GestureRules.classify(p.toList())

    @Test fun single() = assertEquals(Gesture.SINGLE, c(0L to 160L))
    @Test fun double() = assertEquals(Gesture.DOUBLE, c(0L to 140L, 270L to 410L))
    @Test fun triple() = assertEquals(Gesture.TRIPLE, c(0L to 100L, 200L to 300L, 400L to 500L))
    @Test fun four() = assertEquals(Gesture.QUAD, c(0L to 100L, 200L to 300L, 400L to 500L, 600L to 700L))
    @Test fun hold() = assertEquals(Gesture.HOLD, c(0L to 1600L))
    @Test fun holdBoundary() = assertEquals(Gesture.HOLD, c(0L to 500L))
    @Test fun justShort() = assertEquals(Gesture.SINGLE, c(0L to 499L))
    @Test fun clickHold() = assertEquals(Gesture.CLICK_HOLD, c(0L to 100L, 250L to 900L))
    @Test fun holdAfterLateClickIsPlainHold() = assertEquals(Gesture.HOLD, c(0L to 100L, 600L to 1200L))
    @Test fun firstPressHoldIsPlainHold() = assertEquals(Gesture.HOLD, c(0L to 900L))
    @Test fun empty() = assertEquals(null, c())

    /** Fake clock-driven check of the stateful detector. */
    private class Sim {
        val out = ArrayList<Pair<Gesture, Long>>()
        var task: (() -> Unit)? = null
        var at = -1L
        var now = 0L
        val d = GestureDetector({ delay, t -> task = t; at = now + delay }, { task = null }, { g, l -> out.add(g to l) })
        fun advance(to: Long) { if (task != null && at <= to) { val t = task; task = null; now = at; t!!() }; now = to }
    }

    @Test fun detectorDouble() {
        val s = Sim()
        s.d.down(0); s.advance(140); s.d.up(140)
        s.advance(270); s.d.down(270); s.advance(410); s.d.up(410)
        s.advance(1000)
        assertEquals(listOf(Gesture.DOUBLE to 140L), s.out)
    }

    @Test fun detectorHoldFiresEarlyOnce() {
        val s = Sim()
        s.d.down(0); s.advance(600)
        assertEquals(listOf(Gesture.CLICK_HOLD to 500L), s.out)
        s.advance(1600); s.d.up(1600); s.advance(3000)
        assertEquals(listOf(Gesture.CLICK_HOLD to 500L, Gesture.CLICK_HOLD_END to 1600L), s.out)
    }

    @Test fun detectorClickHoldStartsAndEnds() {
        val s = Sim()
        s.d.down(0); s.advance(100); s.d.up(100)
        s.advance(200); s.d.down(200); s.advance(800)
        assertEquals(listOf(Gesture.HOLD to 500L), s.out)
        s.advance(2000); s.d.up(2000); s.advance(3000)
        assertEquals(listOf(Gesture.HOLD to 500L), s.out)
    }

    @Test fun detectorFirstPressHoldStaysHold() {
        val s = Sim()
        s.d.down(0); s.advance(600); s.advance(2000); s.d.up(2000); s.advance(3000)
        assertEquals(listOf(Gesture.CLICK_HOLD to 500L, Gesture.CLICK_HOLD_END to 2000L), s.out)
    }

    @Test fun detectorSlowSecondPressIsPlainHold() {
        val s = Sim()
        s.d.down(0); s.advance(100); s.d.up(100)
        s.advance(1000)   // the single click is reported
        s.d.down(1000); s.advance(1600)
        assertEquals(listOf(Gesture.SINGLE to 100L, Gesture.CLICK_HOLD to 500L), s.out)
    }
}

class DeduperTest {
    @Test fun otherSourceSamePressDropped() {
        val d = Deduper()
        assertEquals(true, d.accept("a11y", true, 1000))
        assertEquals(false, d.accept("media", true, 1020))
        assertEquals(true, d.accept("a11y", false, 1000)) // UP is a different action
        assertEquals(false, d.accept("media", false, 1000))
    }
    @Test fun nextPressKept() {
        val d = Deduper()
        assertEquals(true, d.accept("media", true, 1000))
        assertEquals(true, d.accept("media", true, 1270))
        assertEquals(true, d.accept("a11y", true, 3000))
    }
    @Test fun sameSourceNeverDropped() {
        val d = Deduper()
        assertEquals(true, d.accept("media", true, 1000))
        assertEquals(true, d.accept("media", true, 1100))
    }
}
