package com.faka.invisible.write

import com.faka.invisible.oskit.testing.FakeLoopAudio
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

/** The letter loop with an AI "complete" source: "hay" brings "how are you" into the first items of the next cycle. */
class LoopAiTest {
    private val audio = FakeLoopAudio(latencyMs = 60.0)
    private val logs = ArrayList<String>()
    private val asked = ArrayList<String>()
    private var result: String? = null
    private val words = listOf("hello" to -2.0, "hi" to -2.5, "there" to -3.0, "yes" to -4.0, "had" to -3.5, "hay" to -4.5, "happy" to -4.0)
    private val lm = object : Ranker { override fun ranked(context: List<String>) = words }
    private var frame = Frame()
    private val sources = listOf<GuessSource>(CompleteSource({ done, letters, _, _ -> val typed = (done + " " + letters).trim(); asked += typed; if (typed == "hay") listOf("how are you", "hayden lee", "what is up") else emptyList() }, { 1000L }, run = { it() }))
    private val p by lazy {
        LoopProgram(lm, object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) {}
            override fun cue(c: TypeCue) {}
            override fun readBack(text: String) {}
            override fun log(event: String, vararg fields: Any?) { logs.add(event + " " + fields.joinToString(" ")) }
            override fun finished(text: String) { result = text }
            override fun sources(req: Request, then: (String, List<Guess>) -> Unit) { sources.forEach { s -> s.ask(req) { then(s.id, it) } } }
        }, audio, audio, frame, object : LoopStore { override fun load() = emptyList<Double>(); override fun save(offsets: List<Double>) {} }, Mixer()) {}
    }

    private fun running() { var n = 0; while (audio.played.isEmpty() && n++ < 200) audio.advance(250) }
    private fun press(at: Long) { audio.advance(at - audio.now); p.down(audio.now); audio.advance(80); p.up(audio.now) }
    private fun pick(text: String, done: () -> Boolean) {
        running(); var n = 0
        while (!done() && n < 14) {
            var g = 0; while (audio.played.count { it.text == text } < 2 && g++ < 100) audio.advance(100)
            val first = audio.heardAt(text, 0); val cyc = audio.heardAt(text, 1) - first
            var at = first + 50
            while (at < audio.now) at += cyc
            press(at.toLong()); n++
        }
        assertTrue("never picked $text", done())
    }

    @Test fun hayGivesHowAreYouInTheFirstItems() {
        p.start(); running()
        pick("h") { logs.any { it.startsWith("choice l:h") } }
        pick("a") { logs.any { it.startsWith("choice l:a") } }
        pick("y") { logs.any { it.startsWith("choice l:y") } }
        assertEquals("hay", asked.last())
        val n = audio.played.size
        var t = 0; while (audio.played.size == n && t++ < 100) audio.advance(250)
        audio.advance(2500)
        val items = audio.played.drop(n).map { it.text }.distinct()
        assertEquals("how are you", items.first())
        assertTrue(items.indexOf("hayden lee") in 1..2)
        assertFalse("does not fit the letters", items.contains("what is up"))
        pick("how are you") { p.text() == "How are you" }
        assertTrue(logs.any { it.startsWith("source pick=complete rank=0") })
    }

    @Test fun anAnswerThatArrivesLateWaitsForTheNextCycle() {
        var late: ((String, List<Guess>) -> Unit)? = null
        val q = LoopProgram(lm, object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) {}
            override fun cue(c: TypeCue) {}
            override fun readBack(text: String) {}
            override fun finished(text: String) {}
            override fun sources(req: Request, then: (String, List<Guess>) -> Unit) { late = then }
        }, audio, audio, Frame(), object : LoopStore { override fun load() = emptyList<Double>(); override fun save(offsets: List<Double>) {} }, Mixer()) {}
        q.start(); running(); audio.advance(1000)
        late!!("lists", listOf(Guess("see you soon")))
        audio.advance(50)
        assertFalse("not at once, in the middle of a cycle", audio.played.any { it.text == "see you soon" })
        audio.advance(8000)
        assertTrue(audio.played.any { it.text == "see you soon" })
    }
}
