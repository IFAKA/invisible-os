package com.faka.invisible.write

import com.faka.invisible.voicekit.*

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

    private var empty = false
    private val lm = object : Ranker {
        override fun ranked(context: List<String>): List<Pair<String, Double>> {
            if (empty) return emptyList()
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
        override fun say(text: Phrase) { said.add(text.text) }
        override fun cue(c: TypeCue) { cues.add(c) }
        override fun finished(text: String) { result = text }
        override fun phrases(text: String, then: (List<String>) -> Unit) { asked.add(text); if (endings.isNotEmpty()) then(endings) }
    }, t, onEnded = {})

    private val said = ArrayList<String>()
    private val asked = ArrayList<String>()
    private var endings: List<String> = emptyList()
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
        assertEquals(listOf("Letters"), said)   // Done is last: used once, at the end
        t.advance(TypeProgram.MENU_GAP_MS * 2)
        assertEquals(listOf("Letters", "Delete", "Done"), said)
        clicks(1)
        assertEquals("Hello", result)
        assertTrue(!p.active)
    }

    @Test fun menuReadsLettersDeleteDoneAndAClickPicksTheLastSpoken() {
        p.start(); clicks(1); said.clear(); clicks(3)
        t.advance(TypeProgram.MENU_GAP_MS)
        assertEquals(listOf("Letters", "Delete"), said)
        clicks(1)                                   // Delete: the last word goes
        assertEquals("", p.text())
        assertEquals("hello" to "thanks", pairs.last())
    }

    @Test fun aHoldPicksTheRightWordWhileTheButtonIsDownAndHoldingOnDoesNothingMore() {
        p.start(); cues.clear()
        p.down(t.now); t.advance(Pacing.HOLD_MS + 50)
        assertTrue(cues.contains(TypeCue.PICK_RIGHT))
        t.advance(4000)
        assertTrue(p.active); assertEquals(1, cues.count { it == TypeCue.PICK_RIGHT })
        p.up(t.now)
        assertEquals(1, cues.count { it == TypeCue.PICK_RIGHT }); assertTrue(p.active)
    }

    @Test fun menuLettersItemSpellsAndThenOffersWords() {
        p.start(); said.clear(); clicks(3)
        assertEquals(listOf("Letters"), said)
        clicks(1)   // Letters is first, so one click picks it
        assertTrue(cues.contains(TypeCue.LETTERS))
        val (l, _) = pairs.last()
        assertEquals(1, l.length)
        said.clear(); clicks(3)
        assertEquals("Words", said.last())          // while the letters are offered, the first item is the way back
        clicks(1)
        assertTrue(cues.contains(TypeCue.WORDS))
    }

    @Test fun holdOrThreeClicksCloseTheMenuWithoutAnAction() {
        p.start(); clicks(3); val n = pairs.size
        clicks(3)
        assertEquals(n + 1, pairs.size); assertTrue(p.active)
        clicks(3); hold(700)
        assertEquals(n + 2, pairs.size); assertTrue(p.active)
        assertEquals("", p.text())
    }

    @Test fun twoClicksInTheMenuGoToThePreviousItem() {
        p.start(); clicks(3); val n = pairs.size; said.clear()
        clicks(2); t.advance(Pacing.SKIP_MS + 50)
        assertEquals(n, pairs.size); assertEquals(listOf("Leave"), said)   // before the first item: the last
    }

    @Test fun afterTheReplaysAreUsedItSaysWhereYouAreOnce() {
        p.start(); said.clear()
        t.advance(TypeProgram.REPLAY_MS * 10)
        assertEquals(listOf(TypeVoice.STILL_HERE.text), said)
    }

    private fun spellOneLetterWithNoWordsKnown() { empty = true; p.start(); clicks(3); clicks(1); said.clear(); clicks(1) }   // menu, Letters, the first letter

    @Test fun lettersNoWordStartsWithAreKeptAndSpelledNotWrittenAsAWord() {
        spellOneLetterWithNoWordsKnown()
        assertTrue(said.last().startsWith("No word yet. "))
        assertEquals("", p.text())
    }

    @Test fun nextWordInTheMenuTurnsTheKeptLettersIntoAWord() {
        spellOneLetterWithNoWordsKnown(); said.clear(); clicks(3); t.advance(TypeProgram.MENU_GAP_MS + 50)   // Words, then Next word
        assertEquals("Next word", said.last())
        clicks(1)
        assertEquals(1, p.text().length)
    }

    @Test fun menuClosesByItselfAfterTwoSilentLoops() {
        p.start(); clicks(3); val n = pairs.size
        t.advance(TypeProgram.MENU_GAP_MS * 8 + 50)
        assertEquals(n + 1, pairs.size); assertTrue(p.active)
    }

    @Test fun fourOrFiveClicksDoNothingButReplay() {
        p.start(); clicks(1); val n = pairs.size
        clicks(4); clicks(5)
        assertTrue(p.active); assertEquals("Hello", p.text()); assertEquals(n + 2, pairs.size)
    }

    @Test fun theMenuLeaveItemLeavesWithNothing() {
        p.start(); clicks(1); clicks(3)
        t.advance(TypeProgram.MENU_GAP_MS * 3)
        clicks(1)
        assertEquals("", result)
        assertTrue(!p.active)
    }

    @Test fun silenceReplaysThePair() {
        p.start(); val n = pairs.size
        t.advance(TypeProgram.REPLAY_MS + 10)
        assertEquals(n + 1, pairs.size)
    }

    @Test fun noEndingsMeansNoPhrasesItem() {
        p.start(); clicks(1); said.clear(); clicks(3)
        t.advance(TypeProgram.MENU_GAP_MS * 2)
        assertEquals(listOf("Letters", "Delete", "Done"), said)
    }

    private fun program(frame: Frame, sug: List<String> = emptyList(), ended: () -> Unit = {}) = TypeProgram(lm, object : TypeOut {
        override fun pair(left: String, right: String?) { pairs.add(left to right) }
        override fun hush() {}
        override fun prefetch(words: List<String>) {}
        override fun say(text: Phrase) { said.add(text.text) }
        override fun cue(c: TypeCue) { cues.add(c) }
        override fun finished(text: String) { result = text }
        override fun suggestions() = sug
    }, t, frame, ended)

    @Test fun aPasswordFieldIsRefusedAndNothingIsOffered() {
        var ended = false
        val q = program(Frame(kind = FieldKind.PASSWORD)) { ended = true }
        q.start()
        assertTrue(ended); assertTrue(!q.active); assertTrue(pairs.isEmpty()); assertEquals(listOf(TypeVoice.PASSWORD.text), said)
    }

    @Test fun aNumberFieldOffersDigitsAndWritesThemWithoutSpaces() {
        val q = program(Frame(kind = FieldKind.NUMBER)); q.start()
        assertEquals("1" to "2", pairs.last())
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(500)   // click: 1
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(500)   // click: 1 again
        assertEquals("11", q.text())
    }

    @Test fun aDecimalFieldOffersAPointAndWritesIt() {
        val q = program(Frame(kind = FieldKind.DECIMAL)); q.start()
        repeat(5) { repeat(2) { q.down(t.now); t.advance(80); q.up(t.now); t.advance(100) }; t.advance(600) }   // two clicks = the next pair
        assertEquals("point" to null, pairs.last())
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(600)   // click: the point
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(600)
        assertEquals(".1", q.text())
    }

    @Test fun anEmailFieldStartsWithWhatYouUsedThere() {
        val q = program(Frame(app = "mail", kind = FieldKind.EMAIL), listOf("ana@x.com", "bea@y.com")); q.start()
        assertEquals("ana@x.com" to "bea@y.com", pairs.last())
    }

    @Test fun inAChatTheRepliesFromTheBigModelComeFirstWhenReady() {
        val q = TypeProgram(lm, object : TypeOut {
            override fun pair(left: String, right: String?) { pairs.add(left to right) }
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) {}
            override fun cue(c: TypeCue) {}
            override fun finished(text: String) {}
            override fun phrases(text: String, then: (List<String>) -> Unit) { then(listOf("sí, voy", "no puedo hoy")) }
        }, t, Frame(app = "wa", around = listOf("¿Vienes a cenar?"), hasField = true)) {}
        q.start()
        assertEquals("sí, voy" to "no puedo hoy", pairs.last())
    }

    @Test fun aWordJustWrittenIsNotOfferedAgainAtOnce() {
        p.start(); val first = pairs.last().first
        clicks(1)
        assertTrue(pairs.last().first != first && pairs.last().second != first)
    }

    @Test fun theBigModelsNextWordsReachTheRankerAndAskAgainWhenTheTextMoved() {
        val hints = ArrayList<List<String>>(); val asked = ArrayList<String>()
        var pending: ((List<String>) -> Unit)? = null
        val ranker = object : Ranker {
            override fun ranked(context: List<String>) = lm.ranked(context)
            override fun hint(words: List<String>) { hints.add(words) }
        }
        val q = TypeProgram(ranker, object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) {}
            override fun cue(c: TypeCue) {}
            override fun finished(text: String) {}
            override fun nextWords(text: String, then: (List<String>) -> Unit) { asked.add(text); pending = then }
        }, t) {}
        q.start()
        assertEquals(listOf(""), asked)
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(500)   // the player picks a word while the big model is still thinking
        assertEquals(listOf(""), asked)                              // one request at a time
        pending!!.invoke(listOf("cenar", "hora"))
        assertEquals(listOf(listOf("cenar", "hora")), hints)
        assertEquals(2, asked.size); assertEquals("hello", asked.last())   // the text moved on: ask again with the new text
    }

    // ---- write in English, send the translation only after a click ----
    private val learned = ArrayList<String>()
    private val readBacks = ArrayList<String>()
    private var tr: Translation = Translation.Same
    private var trAsked = 0
    private fun translating() = TypeProgram(lm, object : TypeOut {
        override fun pair(left: String, right: String?) { pairs.add(left to right) }
        override fun hush() {}
        override fun prefetch(words: List<String>) {}
        override fun say(text: Phrase) { said.add(text.text) }
        override fun cue(c: TypeCue) { cues.add(c) }
        override fun finished(text: String) { result = text }
        override fun translate(text: String, then: (Translation) -> Unit) { trAsked++; then(tr) }
        override fun readBack(text: String) { readBacks.add(text) }
        override fun written(text: String) { learned.add(text) }
    }, t, onEnded = {})

    private fun writeHelloAndDone(q: TypeProgram) {
        q.start(); q.down(t.now); t.advance(80); q.up(t.now); t.advance(500)   // click: Hello
        repeat(3) { q.down(t.now); t.advance(80); q.up(t.now); t.advance(120) }; t.advance(400)   // menu: Letters, Delete, Done
        t.advance(TypeProgram.MENU_GAP_MS * 2)
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(500)   // Done
    }

    @Test fun aTranslationIsReadBackAndSentOnlyAfterAClick() {
        tr = Translation.Done("Hola"); val q = translating(); writeHelloAndDone(q)
        assertEquals(listOf("Hola"), readBacks); assertEquals(null, result); assertTrue(q.active)
        assertEquals(TypeVoice.CONFIRM.text, said.last())
        q.down(t.now); t.advance(80); q.up(t.now); t.advance(500)
        assertEquals("Hola", result); assertEquals(listOf("Hello"), learned); assertTrue(!q.active)
    }

    @Test fun holdAfterTheReadBackGoesBackToWritingAndSendsNothing() {
        tr = Translation.Done("Hola"); val q = translating(); writeHelloAndDone(q)
        q.down(t.now); t.advance(700); q.up(t.now); t.advance(20)
        assertEquals(null, result); assertTrue(q.active); assertEquals("Hello", q.text())
    }

    @Test fun aFailedTranslationIsSpokenAndNothingIsSent() {
        tr = Translation.Failed(TypeVoice.TRANSLATION_MISSING); val q = translating(); writeHelloAndDone(q)
        assertEquals(TypeVoice.TRANSLATION_MISSING.text, said.last()); assertEquals(null, result); assertTrue(q.active); assertTrue(readBacks.isEmpty())
    }

    @Test fun whenNoTranslationIsNeededTheEnglishIsSentAsWritten() {
        tr = Translation.Same; val q = translating(); writeHelloAndDone(q)
        assertEquals("Hello", result); assertEquals(listOf("Hello"), learned)
    }
}
