package com.faka.invisible.write

import com.faka.invisible.voicekit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of the letters offered while spelling: by the words that fit, then by letter pairs for a name. */
class LetterOrderTest {
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

    private val t = Fake()
    private val pairs = ArrayList<Pair<String, String?>>()
    // tiny masses (like a real model) and a vocabulary where the common-letter prior would win
    private val words = listOf("android" to -20.0, "and" to -3.0, "apple" to -4.0, "sea" to -5.0, "red" to -6.0, "arrow" to -7.0, "billing" to -21.0, "bill" to -9.0, "bell" to -8.0, "ill" to -8.0, "hill" to -8.5, "mill" to -9.5, "all" to -2.0)
    private val lm = object : Ranker { override fun ranked(context: List<String>) = words.sortedByDescending { it.second } }
    private val p = TypeProgram(lm, object : TypeOut {
        override fun pair(left: String, right: String?) { pairs.add(left to right) }
        override fun hush() {}
        override fun prefetch(words: List<String>) {}
        override fun say(text: Phrase) {}
        override fun cue(c: TypeCue) {}
        override fun finished(text: String) {}
    }, t, onEnded = {})
    private fun click() { p.down(t.now); t.advance(80); p.up(t.now); t.advance(120) }
    private fun clicks(n: Int) { repeat(n) { click() }; t.advance(400) }

    /** Letters mode, then spell [word] by picking the left letter each time it is first in the pair, or paging until it is. */
    private fun spell(word: String) {
        p.start(); clicks(3); clicks(1)
        for (c in word) {
            var guard = 0
            while (pairs.last().first != c.toString() && pairs.last().second != c.toString() && guard++ < 20) clicks(2)
            if (pairs.last().first == c.toString()) clicks(1) else { p.down(t.now); t.advance(600); p.up(t.now); t.advance(400) }
        }
    }

    @Test fun afterAndroTheLetterIIsInTheFirstPair() {
        spell("andro")
        assertTrue(pairs.last().toList().contains("i"))
    }

    @Test fun anUnknownPrefixFollowsTheLetterPairsNotTheAlphabet() {
        spell("zil")   // no word starts with "zil"; "l" is followed by "l" most often in this list (all, ill, bill, bell...)
        val (l, r) = pairs.last()
        assertEquals("l", l)
        assertNotEquals("a", l)
        assertTrue(r != "b")
    }

    @Test fun letterBigramsRankByWordRank() {
        val g = LetterBigrams(listOf("ab", "ac", "ac"))
        assertTrue(g.weight('a', 'c') > g.weight('a', 'b'))
        assertEquals(0.0, g.weight('b', 'a'), 0.0)
    }
}
