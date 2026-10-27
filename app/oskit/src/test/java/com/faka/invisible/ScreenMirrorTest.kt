package com.faka.invisible

import com.faka.invisible.voicekit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenMirrorTest {
    @Before fun setUp() { ScreenMirror.clear(); ScreenMirror.onChange = null; ScreenMirror.now = { 42L } }
    @After fun tearDown() { ScreenMirror.clear(); ScreenMirror.onChange = null }

    @Test fun startsClosed() { assertFalse(ScreenMirror.active); assertTrue(ScreenMirror.items.isEmpty()); assertNull(ScreenMirror.lastPress) }

    @Test fun showPublishesListAndIndex() {
        ScreenMirror.show(listOf("Talk", "Plan", "Pay"), 1)
        assertTrue(ScreenMirror.active)
        assertEquals(listOf("Talk", "Plan", "Pay"), ScreenMirror.items)
        assertEquals(1, ScreenMirror.index)
    }

    @Test fun indexIsKeptInRange() {
        ScreenMirror.show(listOf("a", "b"), 9)
        assertEquals(1, ScreenMirror.index)
        ScreenMirror.show(emptyList(), 3)
        assertEquals(0, ScreenMirror.index)
    }

    @Test fun pressKeepsListAndStampsTime() {
        ScreenMirror.show(listOf("a"), 0)
        ScreenMirror.pressed("2 clicks")
        assertEquals("2 clicks", ScreenMirror.lastPress)
        assertEquals(42L, ScreenMirror.state.pressAt)
        assertEquals(listOf("a"), ScreenMirror.items)
    }

    @Test fun titleSurvivesNewItemsAndClearResetsAll() {
        ScreenMirror.setTitle("Talk > Mail")
        ScreenMirror.show(listOf("a"), 0)
        assertEquals("Talk > Mail", ScreenMirror.title)
        ScreenMirror.clear()
        assertFalse(ScreenMirror.active); assertEquals("", ScreenMirror.title); assertNull(ScreenMirror.lastPress)
    }

    @Test fun everyChangeCallsOnChange() {
        var n = 0
        ScreenMirror.onChange = { n++ }
        ScreenMirror.show(listOf("a"), 0); ScreenMirror.setTitle("x"); ScreenMirror.pressed("click"); ScreenMirror.clear()
        assertEquals(4, n)
    }

    @Test fun gestureNames() {
        assertEquals("click", ScreenMirror.gestureText(Gesture.SINGLE.name))
        assertEquals("2 clicks", ScreenMirror.gestureText(Gesture.DOUBLE.name))
        assertEquals("click, then hold", ScreenMirror.gestureText(Gesture.CLICK_HOLD.name))
    }

    @Test fun scannerPublishesEveryItemItStarts() {
        val timers = object : Timers { override fun set(key: String, delayMs: Long, task: () -> Unit) {}; override fun clear(key: String) {} }
        val sc = Scanner<String>(timers, { Phrase.content(it) }, object : ScanOut {
            override fun say(index: Int, label: Phrase, spearcon: Boolean) {}
            override fun sayFast(word: Phrase) {}
            override fun tone(t: Tone) {}
            override fun leave() {}
        }, ScanCadence(pauseMs = { 100 }, loops = { 2 }))
        sc.load(listOf("one", "two"))
        sc.speakItem(1)
        assertEquals(listOf("one", "two"), ScreenMirror.items)
        assertEquals(1, ScreenMirror.index)
    }
}
