package com.faka.invisible.write

import com.faka.invisible.logickit.ClickModel
import com.faka.invisible.logickit.ClickProfile
import com.faka.invisible.oskit.testing.FakeLoopAudio
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

/** Write on the letter loop: simulated clicks at the moments the loop speaks the item (fake time, fake sound). */
class LoopProgramTest {
    private val audio = FakeLoopAudio(latencyMs = 60.0)
    private val said = ArrayList<String>()
    private val reads = ArrayList<String>()
    private val logs = ArrayList<String>()
    private var result: String? = null
    private var ended = 0
    private var saved: List<Double> = emptyList()
    private var translation: Translation = Translation.Same
    private val words = listOf("android" to -2.0, "hello" to -2.5, "hi" to -3.0, "there" to -3.5, "home" to -4.0, "hill" to -5.0, "yes" to -6.0, "ice" to -7.0)
    private val lm = object : Ranker { override fun ranked(context: List<String>) = words }
    private val p = LoopProgram(lm, object : TypeOut {
        override fun pair(left: String, right: String?) {}
        override fun hush() {}
        override fun prefetch(words: List<String>) {}
        override fun say(text: Phrase) { said.add(text.text) }
        override fun cue(c: TypeCue) {}
        override fun readBack(text: String) { reads.add(text) }
        override fun log(event: String, vararg fields: Any?) { logs.add(event + " " + fields.joinToString(" ")) }
        override fun finished(text: String) { result = text }
        override fun translate(text: String, then: (Translation) -> Unit) { then(translation) }
    }, audio, audio, Frame(), object : LoopStore {
        override fun load() = emptyList<Double>()
        override fun save(offsets: List<Double>) { saved = offsets }
    }) { ended++ }

    /** Let the intro or the short word finish until the loop plays. */
    private fun running() { var n = 0; while (audio.played.isEmpty() && n++ < 200) audio.advance(250) }

    private fun press(at: Long) { audio.advance(at - audio.now); p.down(audio.now); audio.advance(80); p.up(audio.now) }

    /** Click on the item that says [text] once per cycle, 50 ms after it is heard, until [done] says the pick happened. Returns the clicks. */
    private fun pick(text: String, done: () -> Boolean): Int {
        running()
        var n = 0
        while (!done() && n < 14) {
            val first = audio.heardAt(text, 0); val cyc = audio.heardAt(text, 1) - first
            var at = first + 50
            while (at < audio.now) at += cyc
            press(at.toLong()); n++
        }
        assertTrue("never picked $text; heard $said", done())
        return n
    }

    @Test fun typingHiBySpellingThenSpaceAndSend() {
        p.start()
        pick("h") { p.text().isEmpty() && said.any { it == "H" } }
        pick("i") { said.any { it == "H, I" } }
        pick("Space") { p.text() == "Hi" }
        pick("Send") { reads.isNotEmpty() }
        assertEquals("Hi", reads.last()); assertNull(result)
        press(audio.now + 100)   // a click sends
        assertEquals("Hi", result); assertEquals(1, ended)
        assertTrue(saved.isNotEmpty())   // what was learned is kept
    }

    @Test fun aClickThatDoesNotChooseStartsAFocusRoundThatStillPicksTheLetter() {
        p.start(); running(); audio.advance(1500)
        val full = audio.played.map { it.text }.toSet().size
        pick("h") { logs.any { it.startsWith("focus n=") } }
        assertTrue(logs.toString(), logs.any { Regex("focus n=\\d ms=${Pacing.LOOP_FOCUS_MS}").matches(it.trim()) })
        assertTrue("fewer items than the $full of the full loop", audio.played.map { it.text }.toSet().size < full)
        pick("h") { said.any { it == "H" } }
        assertTrue(logs.any { it.startsWith("choice l:h") })
    }

    @Test fun aWholeWordGuessAddsTheWordAndTheSpace() {
        p.start()
        val n = pick("android") { p.text() == "Android" }
        assertTrue("took $n clicks", n <= 6)
        assertTrue(logs.any { it.startsWith("choice w:android") })
    }

    @Test fun controlsAreLastAndOnlyWhenTheyMakeSense() {
        p.start(); running(); audio.advance(1500)
        val first = audio.played.map { it.text }.distinct()
        assertEquals(listOf("android", "hello", "hi"), first.take(3))   // the three best words first
        assertEquals("Send", first.last()); assertFalse(first.contains("Space")); assertFalse(first.contains("Undo"))
        pick("h") { said.any { it == "H" } }
        running(); audio.advance(1500)
        val later = audio.played.map { it.text }.distinct()
        assertTrue(later.containsAll(listOf("Space", "Undo", "Send")))
        assertEquals(setOf("Space", "Undo", "Send"), later.takeLast(3).toSet())
        assertEquals(listOf("hello", "hi", "home"), later.take(3))   // only words that start with h
    }

    @Test fun undoTakesBackTheLastPick() {
        p.start()
        pick("h") { said.any { it == "H" } }
        pick("Undo") { logs.any { it.startsWith("choice undo") } }
        running(); audio.advance(1500)
        assertEquals("android", audio.played.map { it.text }.first())   // back to the empty prefix
        assertTrue(!audio.played.map { it.text }.contains("Space"))
        // and a word can be undone
        pick("hello") { p.text() == "Hello" }
        pick("Undo") { logs.count { it.startsWith("choice undo") } == 2 }
        assertEquals("", p.text())
    }

    @Test fun sendReadsTheTextBackAndAHoldGoesBackToWriting() {
        p.start()
        pick("hello") { p.text() == "Hello" }
        pick("Send") { reads.isNotEmpty() }
        assertEquals("Hello", reads.last()); assertTrue(said.contains(TypeVoice.CONFIRM.text))
        p.down(audio.now); audio.advance(Pacing.HOLD_MS + 50); p.up(audio.now)
        assertNull(result); assertTrue(p.active)
        audio.advance(3000); assertTrue(audio.played.isNotEmpty())   // the loop runs again
    }

    @Test fun aTranslationIsReadBackInsteadOfTheText() {
        translation = Translation.Done("Hola")
        p.start()
        pick("hello") { p.text() == "Hello" }
        pick("Send") { reads.isNotEmpty() }
        assertEquals("Hola", reads.last())
    }

    @Test fun noClickReadsTheTextSoFarAfterFourCycles() {
        p.start()
        pick("hello") { p.text() == "Hello" }
        audio.advance(5000)
        reads.clear(); said.clear()
        audio.advance(60_000)
        assertEquals("Hello", reads.firstOrNull())
        audio.advance(5000); assertTrue(audio.played.isNotEmpty())   // then the loop goes on
    }

    @Test fun anEmptyTextHintsInsteadOfReading() {
        p.start(); audio.advance(60_000)
        assertTrue(said.contains(TypeVoice.LOOP_HINT.text))
    }

    @Test fun everyClickAndChoiceIsLogged() {
        p.start()
        pick("hello") { p.text() == "Hello" }
        assertTrue(logs.any { it.startsWith("loop-click") })
        assertTrue(logs.any { Regex("choice w:hello \\d+ \\d+ \\d+ \\d+").containsMatchIn(it) })
    }

    @Test fun aPasswordFieldIsRefused() {
        val pw = LoopProgram(lm, object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) { said.add(text.text) }
            override fun cue(c: TypeCue) {}
            override fun finished(text: String) {}
        }, audio, audio, Frame(kind = FieldKind.PASSWORD), object : LoopStore { override fun load() = emptyList<Double>(); override fun save(offsets: List<Double>) {} }) { ended++ }
        pw.start()
        assertTrue(said.contains(TypeVoice.PASSWORD.text)); assertEquals(1, ended); assertFalse(pw.active)
    }

    @Test fun densityIsKeptAsASmallJsonList() {
        assertEquals("[0.120,0.080]", LoopDensity.encode(listOf(0.12, 0.08)))
        assertEquals(listOf(0.12, 0.08), LoopDensity.decode("[0.120,0.080]"))
        assertEquals(emptyList<Double>(), LoopDensity.decode(null)); assertEquals(emptyList<Double>(), LoopDensity.decode("[]"))
    }

    @Test fun startLogsTheSeed() { p.start(); assertTrue(logs.toString(), logs.contains("seed from=default")) }

    @Test fun seedFromProfileOnlyWithoutLearnedClicks() {
        val prof = ClickProfile.of(List(20) { 180.0 + (it % 5) * 20 })
        val few = List(7) { 0.1 }; val many = List(8) { 0.1 }
        assertEquals("profile", LoopSeed.choose(emptyList(), prof).first)
        assertEquals(prof.params(), LoopSeed.choose(few, prof).second)
        assertEquals("write", LoopSeed.choose(many, prof).first)
        assertEquals(ClickModel.Params(), LoopSeed.choose(many, prof).second)
        assertEquals("default", LoopSeed.choose(emptyList(), null).first)
        assertEquals("default", LoopSeed.choose(emptyList(), ClickProfile.of(listOf(100.0, 120.0))).first)
    }
}
