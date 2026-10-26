package com.faka.invisible

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanListTest {
    private fun l() = ScanList(listOf("Messages, 3 unread", "Music", "Timer"))

    @Test fun nextWraps() {
        val s = l()
        assertEquals(1, s.nextIndex()); s.moveTo(2)
        assertTrue(s.isLast); assertEquals(0, s.nextIndex())
    }

    @Test fun loopsCount() {
        val s = l()
        assertEquals(1, s.endLoop()); assertEquals(2, s.endLoop())
        s.resetLoops(); assertEquals(0, s.loops)
    }

    @Test fun firstWord() {
        assertEquals("Messages", ScanList.firstWord("Messages, 3 unread"))
        assertEquals("Music", ScanList.firstWord("  Music "))
        assertEquals("", ScanList.firstWord(""))
    }

    @Test fun fastStepWalksAndWraps() {
        val s = l()
        val a = s.fastStep { it }
        assertEquals(1, a.index); assertEquals("Music", a.text); assertFalse(a.wrapped)
        val b = s.fastStep { it }
        assertEquals(2, b.index); assertFalse(b.wrapped)
        val c = s.fastStep { it }
        assertEquals(0, c.index); assertTrue(c.wrapped)
        assertEquals(0, s.index)
    }
}
