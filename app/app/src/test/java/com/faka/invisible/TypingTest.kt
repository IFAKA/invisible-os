package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTest {
    private class Fake : Timers {
        var now = 0L
        val tasks = HashMap<String, Pair<Long, () -> Unit>>()
        override fun set(key: String, delayMs: Long, task: () -> Unit) { tasks[key] = now + delayMs to task }
        override fun clear(key: String) { tasks.remove(key) }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.entries.filter { it.value.first <= end }.minByOrNull { it.value.first } ?: break
                tasks.remove(next.key); now = next.value.first; next.value.second()
            }
            now = end
        }
    }

    private val lm = object : Ranker {
        override fun ranked(context: List<String>): List<Pair<String, Double>> {
            val ws = if (context.isEmpty()) listOf("hello", "thanks", "yes", "nope", "later", "okay", "call", "nowhere")
            else listOf("you", "mate", "soon", "later", "home", "work", "there", "here")
            return ws.mapIndexed { i, w -> w to -(i + 1).toDouble() }
        }
    }
    private val pairs = ArrayList<Pair<String, String?>>()
    private val cues = ArrayList<TypeCue>()
    private var result: String? = null
    private val t = Fake()
    private val p = TypeProgram(lm, object : TypeOut {
        override fun pair(left: String, right: String?) { pairs.add(left to right) }
        override fun hush() {}
        override fun prefetch(words: List<String>) {}
        override fun say(text: String) { said.add(text) }
        override fun cue(c: TypeCue) { cues.add(c) }
        override fun finished(text: String) { result = text }
    }, t, onEnded = {})

    private val said = ArrayList<String>()
    private fun click() { p.down(t.now); t.advance(80); p.up(t.now); t.advance(120) }
    private fun clicks(n: Int) { repeat(n) { click() }; t.advance(400) }
    private fun hold(ms: Long) { p.down(t.now); t.advance(ms); p.up(t.now); t.advance(20) }

    @Test fun clickPicksLeftHoldPicksRight() {
        p.start()
        assertEquals("hello" to "thanks", pairs.last())
        clicks(1)
        assertEquals("you" to "mate", pairs.last())
        hold(700)
        assertEquals("Hello mate", p.text())
    }

    @Test fun twoClicksShowNextPair() {
        p.start(); clicks(2)
        assertEquals("yes" to "nope", pairs.last())
    }

    @Test fun threeClicksOpenTheMenuAndAClickOnDonePicksDone() {
        p.start(); clicks(1); said.clear(); clicks(3)
        assertEquals(listOf("Done"), said)
        clicks(1)
        assertEquals("Hello", result)
        assertTrue(!p.active)
    }

    @Test fun menuReadsDoneDeleteLettersAndAClickPicksTheLastSpoken() {
        p.start(); clicks(1); said.clear(); clicks(3)
        t.advance(TypeProgram.MENU_GAP_MS)
        assertEquals(listOf("Done", "Delete"), said)
        clicks(1)                                   // Delete: the last word goes
        assertEquals("", p.text())
        assertEquals("hello" to "thanks", pairs.last())
    }

    @Test fun menuLettersItemSpellsAndThenOffersWords() {
        p.start(); said.clear(); clicks(3)
        t.advance(TypeProgram.MENU_GAP_MS * 2)
        assertEquals(listOf("Done", "Delete", "Letters"), said)
        clicks(1)
        assertTrue(cues.contains(TypeCue.LETTERS))
        val (l, _) = pairs.last()
        assertEquals(1, l.length)
        said.clear(); clicks(3)
        t.advance(TypeProgram.MENU_GAP_MS * 2)
        assertEquals("Words", said.last())          // while the letters are offered, the third item is the way back
        clicks(1)
        assertTrue(cues.contains(TypeCue.WORDS))
    }

    @Test fun holdOrTwoClicksCloseTheMenuWithoutAnAction() {
        p.start(); clicks(3); val n = pairs.size
        clicks(2)
        assertEquals(n + 1, pairs.size); assertTrue(p.active)
        clicks(3); hold(700)
        assertEquals(n + 2, pairs.size); assertTrue(p.active)
        assertEquals("", p.text())
    }

    @Test fun menuClosesByItselfAfterTwoSilentLoops() {
        p.start(); clicks(3); val n = pairs.size
        t.advance(TypeProgram.MENU_GAP_MS * 6 + 50)
        assertEquals(n + 1, pairs.size); assertTrue(p.active)
    }

    @Test fun fourOrFiveClicksDoNothingButReplay() {
        p.start(); clicks(1); val n = pairs.size
        clicks(4); clicks(5)
        assertTrue(p.active); assertEquals("Hello", p.text()); assertEquals(n + 2, pairs.size)
    }

    @Test fun holdThreeSecondsLeavesWithNothing() {
        p.start(); clicks(1); hold(3200)
        assertEquals("", result)
        assertTrue(!p.active)
    }

    @Test fun silenceReplaysThePair() {
        p.start(); val n = pairs.size
        t.advance(TypeProgram.REPLAY_MS + 10)
        assertEquals(n + 1, pairs.size)
    }
}
