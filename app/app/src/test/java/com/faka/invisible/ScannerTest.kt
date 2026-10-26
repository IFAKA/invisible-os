package com.faka.invisible

import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

class ScannerTest {
    private class Rig(ceremony: Boolean, loops: Int = 2) {
        var now = 0L
        val tasks = HashMap<String, Pair<Long, () -> Unit>>()
        val log = ArrayList<String>()
        val timers = object : Timers {
            override fun set(key: String, delayMs: Long, task: () -> Unit) { tasks[key] = now + delayMs to task }
            override fun clear(key: String) { tasks.remove(key) }
        }
        lateinit var sc: Scanner<String>
        init {
            sc = Scanner(timers, { Phrase.content(it) }, object : ScanOut {
                override fun say(index: Int, label: Phrase, spearcon: Boolean) { log.add("say ${label.text}"); if (!ceremony) sc.itemDone() }
                override fun sayFast(word: Phrase) { log.add("fast ${word.text}") }
                override fun tone(t: Tone) { log.add("tone $t") }
                override fun leave() { log.add("leave") }
            }, ScanCadence(pauseMs = { 100 }, loops = { loops }, ceremony = ceremony))
            sc.load(listOf("a b", "c"))
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.entries.filter { it.value.first <= end }.minByOrNull { it.value.first } ?: break
                tasks.remove(next.key); now = maxOf(now, next.value.first); next.value.second()
            }
            now = end
        }
    }

    @Test fun menuStyleReadsAllThenLeavesAfterTheLoops() {
        val r = Rig(ceremony = false)
        r.sc.speakItem(0); r.advance(1000)
        assertEquals(listOf("say a b", "say c", "say a b", "say c", "leave"), r.log)
    }

    @Test fun osStyleTicksBetweenItemsAndDingsAtTheEnd() {
        val r = Rig(ceremony = true, loops = 1)
        r.sc.speakItem(0); r.sc.itemDone(); r.advance(100)
        r.sc.itemDone(); r.advance(100)   // after the last item: ding, then leave one pause later
        r.advance(100)
        assertEquals(listOf("say a b", "tone TICK", "say c", "tone DING", "leave"), r.log)
    }

    @Test fun fastScrollSaysFirstWordsWithTicksAndWraps() {
        val r = Rig(ceremony = true)
        r.sc.startFast(); r.advance(Pacing.FAST_MS)
        assertTrue(r.log.first().startsWith("tone"))
        assertEquals("fast c", r.log[1])
        r.advance(Pacing.FAST_MS)
        assertEquals(listOf("tone TICK", "fast c", "tone RESTART", "fast a"), r.log.take(4))
        assertTrue(r.sc.endFast()); assertFalse(r.sc.endFast())
    }
}
