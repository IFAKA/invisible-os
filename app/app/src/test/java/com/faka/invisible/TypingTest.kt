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
            val ws = if (context.isEmpty()) listOf("hello", "thanks", "yes", "no", "later", "ok", "call", "now")
            else listOf("you", "me", "now", "later", "home", "work", "there", "here")
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
        override fun say(text: String) {}
        override fun cue(c: TypeCue) { cues.add(c) }
        override fun finished(text: String) { result = text }
    }, t, onEnded = {})

    private fun click() { p.down(t.now); t.advance(80); p.up(t.now); t.advance(120) }
    private fun clicks(n: Int) { repeat(n) { click() }; t.advance(400) }
    private fun hold(ms: Long) { p.down(t.now); t.advance(ms); p.up(t.now); t.advance(20) }

    @Test fun clickPicksLeftHoldPicksRight() {
        p.start()
        assertEquals("hello" to "thanks", pairs.last())
        clicks(1)
        assertEquals("you" to "me", pairs.last())
        hold(700)
        assertEquals("Hello me", p.text())
    }

    @Test fun twoClicksShowNextPair() {
        p.start(); clicks(2)
        assertEquals("yes" to "no", pairs.last())
    }

    @Test fun fourClicksFinish() {
        p.start(); clicks(1); clicks(4)
        assertEquals("Hello", result)
        assertTrue(!p.active)
    }

    @Test fun fiveClicksDeleteLastWord() {
        p.start(); clicks(1); clicks(5)
        assertEquals("", p.text())
        assertEquals("hello" to "thanks", pairs.last())
    }

    @Test fun threeClicksShowLettersThenWordsWithThatPrefix() {
        p.start(); clicks(3)
        assertEquals(TypeCue.LETTERS, cues.last { it == TypeCue.LETTERS })
        val (l, _) = pairs.last()
        assertEquals(1, l.length)
        clicks(1)                                   // pick the left letter; words now start with it
        assertTrue(pairs.last().first.startsWith(l))
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
