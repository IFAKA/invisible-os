package com.faka.invisible.write

import com.faka.invisible.oskit.testing.FakeLoopAudio
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

/** A person with no saved level: one channel in both ears, normal speech, 600 ms an item; five clean picks raise it and it is saved. */
class LoopBeginnerTest {
    private val audio = FakeLoopAudio(latencyMs = 60.0)
    private var level = 0
    private val said = ArrayList<String>()
    private val words = listOf("android" to -2.0, "hello" to -2.5, "hi" to -3.0, "there" to -3.5)
    private val p = LoopProgram(object : Ranker { override fun ranked(context: List<String>) = words }, object : TypeOut {
        override fun pair(left: String, right: String?) {}
        override fun hush() {}
        override fun prefetch(words: List<String>) {}
        override fun say(text: Phrase) { said.add(text.text) }
        override fun cue(c: TypeCue) {}
        override fun readBack(text: String) {}
        override fun finished(text: String) {}
    }, audio, audio, Frame(), object : LoopStore {
        override fun load() = emptyList<Double>(); override fun save(offsets: List<Double>) {}
        override fun level() = level; override fun saveLevel(level: Int) { this@LoopBeginnerTest.level = level }
    }) {}

    private fun running() { var n = 0; while (audio.played.isEmpty() && n++ < 200) audio.advance(250) }
    private fun click(at: Long) { audio.advance(at - audio.now); p.down(audio.now); audio.advance(80); p.up(audio.now) }
    private fun pick(text: String, done: () -> Boolean) {
        running(); var n = 0
        while (!done() && n++ < 14) {
            val first = audio.heardAt(text, 0); val cyc = audio.heardAt(text, 1) - first
            var at = first + 50; while (at < audio.now) at += cyc
            click(at.toLong())
        }
        assertTrue("never picked $text", done())
    }

    @Test fun theFirstCycleIsMonoSlowAndGuessesAreAlone() {
        p.start(); running(); audio.advance(200)
        val at = { t: String -> audio.played.filter { it.text == t }.minOf { it.frame } * 1000.0 / audio.sampleRate }
        assertTrue("all in the middle", audio.played.all { it.pan == 0.0 })
        val letters = ('a'..'z').filter { audio.played.any { p -> p.text == it.toString() } }.map { at(it.toString()) }.sorted()
        assertTrue(letters.size > 10)
        letters.zipWithNext().forEach { (x, y) -> assertEquals(0.0, (y - x) % 600.0, 0.5); assertTrue(y - x >= 599.0) }   // one letter after the other, 600 ms slots
        assertEquals(1.0, audio.speeds.getValue("a"), 0.0); assertEquals(1.0, audio.speeds.getValue("android"), 0.0)
        assertTrue("guesses come before the letters", at("android") < at("a") && at("hello") < at("a"))
    }

    @Test fun fiveCleanPicksRaiseTheLevelAndSaveIt() {
        p.start()
        val letters = listOf("a", "b", "c", "d", "e")
        for ((i, l) in letters.withIndex()) { val n = said.size; pick(l) { said.size > n } ; assertEquals(if (i < 4) 0 else 1, level) }
    }
}
