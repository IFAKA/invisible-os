package com.faka.invisible.write

import org.junit.Assert.*
import org.junit.Test

class SourcesTest {
    private fun ask(s: GuessSource, req: Request): List<Guess>? { var got: List<Guess>? = null; s.ask(req) { got = it }; return got }
    private val chat = Frame(app = "wa", thread = "Ana", around = listOf("Ana: how are you?"))

    @Test fun completeAsksWithTheTypedLettersAndTheChat() {
        val calls = ArrayList<Triple<String, String, String>>(); val trace = ArrayList<String>()
        val s = CompleteSource({ d, l, c, _ -> calls += Triple(d, l, c); listOf("how are you", "hay fever") }, { 1000L }, { trace += it }, run = { it() })
        val g = ask(s, Request(chat, emptyList(), "hay"))!!
        assertEquals(listOf("how are you", "hay fever"), g.map { it.text })
        assertEquals(listOf(Triple("", "hay", "Ana: how are you?")), calls)
        assertTrue(trace.single().matches(Regex("ai complete \\d+ms 2")))
    }

    @Test fun theFinishedWordsAndTheLettersGoApartWithTheTimeFromTheLoop() {
        val calls = ArrayList<Triple<String, String, Long>>(); var budget = 1700L
        val s = CompleteSource({ d, l, _, ms -> calls += Triple(d, l, ms); listOf("see you tomorrow") }, { budget }, run = { it() })
        ask(s, Request(chat, listOf("see", "you"), "tom"))
        budget = 2500L; ask(s, Request(chat, listOf("see", "you"), "to"))
        assertEquals(listOf(Triple("see you", "tom", 1700L), Triple("see you", "to", 2500L)), calls)
    }

    @Test fun aPrivateReplyNeverReachesTheAi() {
        var called = 0
        val s = CompleteSource({ _, _, _, _ -> called++; listOf("x") }, { 1000L }, run = { it() })
        val priv = Frame.reply("com.bank", "Bank", "Your code is 123456", hidden = true)
        assertTrue(priv.private)
        assertNull(ask(s, Request(priv, emptyList(), "hay")))
        assertNull(ask(s, Request(Frame(kind = FieldKind.PASSWORD, hasField = true), emptyList(), "hay")))
        assertNull(ask(s, Request(Frame(kind = FieldKind.EMAIL), emptyList(), "hay")))
        assertNull(ask(s, Request(chat, emptyList(), "")))   // nothing typed
        assertEquals(0, called)
        assertNotNull(ask(s, Request(Frame.reply("wa", "Ana", "hi", hidden = false), emptyList(), "hay")))
    }

    @Test fun aSlowAnswerIsDroppedAfterTheTimeout() {
        val trace = ArrayList<String>()
        val s = CompleteSource({ _, _, _, _ -> Thread.sleep(400); listOf("late") }, { 50L }, { trace += it }, run = { it() })
        val t0 = System.currentTimeMillis()
        assertNull(ask(s, Request(chat, emptyList(), "hay")))
        assertTrue(System.currentTimeMillis() - t0 < 300)
        assertTrue(trace.single().endsWith(" 0"))
    }

    @Test fun aFailingAiGivesNoGuesses() {
        val s = CompleteSource({ _, _, _, _ -> throw java.io.IOException("down") }, { 1000L }, run = { it() })
        assertNull(ask(s, Request(chat, emptyList(), "hay")))
    }

    @Test fun listsMatchWordsByPrefixAndNamesByFirstLetters() {
        val s = ListSource("lists", { listOf("Ana", "Ana Lopez", "Bob") }, run = { it() })
        assertNull(ask(s, Request(Frame(), emptyList(), "")))   // the first ask loads; nothing typed
        assertEquals(listOf("Ana", "Ana Lopez"), ask(s, Request(Frame(), emptyList(), "an"))!!.map { it.text })
        assertEquals(listOf("Ana Lopez"), ask(s, Request(Frame(), emptyList(), "al"))!!.map { it.text })
    }

    @Test fun screenSuggestionsAreNeverReadForAPassword() {
        var read = 0
        val s = ScreenSource({ read++; listOf("billie jean") }, run = { it() })
        assertNull(ask(s, Request(Frame(kind = FieldKind.PASSWORD), emptyList(), "b")))
        assertEquals(0, read)
        assertEquals(listOf("billie jean"), ask(s, Request(Frame(), emptyList(), "b"))!!.map { it.text })
    }
}
