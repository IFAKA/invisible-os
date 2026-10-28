package com.faka.invisible

import com.faka.invisible.claude.Computer
import com.faka.invisible.oskit.Notif
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiHelpTest {
    private val pc = Computer("pc", "http://x", "tok")
    private val long = (1..45).joinToString(" ") { "w$it" }
    private fun n(k: String, text: String = "hi", hidden: Boolean = false, title: String = "T$k", at: Long = 1L) = Notif(k, "p", "App", title, text, at, hidden = hidden)

    private class Fake(var line: String = "2 chats", var fail: Boolean = false, var emptyReplies: Boolean = false) : AiHelp.AiClient {
        val digestCalls = ArrayList<List<Triple<String, String, String>>>(); val shortCalls = ArrayList<Pair<String, String>>(); val replyCalls = ArrayList<Triple<String, String, String>>()
        override fun digest(items: List<Triple<String, String, String>>): String { digestCalls += items; if (fail) throw IOException("x"); return line }
        override fun short(text: String, context: String): String { shortCalls += text to context; if (fail) throw IOException("x"); return "short one" }
        override fun replies(text: String, lang: String, context: String): Pair<List<String>, List<String>> {
            replyCalls += Triple(text, lang, context); if (fail) throw IOException("x")
            return if (emptyReplies) emptyList<String>() to emptyList() else listOf("Yes", "No", "Maybe") to listOf("Send it")
        }
    }

    /** The phone model: [answer] says the JSON for a prompt; every prompt is kept. */
    private class Phone(var ready: Boolean = true, var answer: (String) -> String = { "" }) : AiHelp.PhoneAi {
        val prompts = ArrayList<String>(); val replyCalls = ArrayList<Triple<String, String, String>>()
        override fun available() = ready
        override fun generate(prompt: String): String { prompts += prompt; return answer(prompt) }
        override fun replies(text: String, lang: String, context: String): List<String> { replyCalls += Triple(text, lang, context); return listOf("Sí", "No", "Tal vez") }
        override fun tasks(text: String, lang: String, context: String) = listOf("Enviar")
    }

    private var clock = 0L
    private fun help(c: Computer?, f: Fake, on: Boolean = true, phone: Phone? = null) = AiHelp({ c }, { on }, phone, { it() }, { clock }, { f })

    @Test fun noComputerNoPhoneMeansNoCallsAndNoAnswers() {
        val f = Fake(); val h = help(null, f); val all = listOf(n("a"), n("b", long))
        h.changed(all)
        assertEquals("", h.digest(all)); assertEquals("", h.short(all[1], all)); assertEquals(0, f.digestCalls.size + f.shortCalls.size)
    }

    @Test fun switchOffMeansNoAiAnywhere() {
        val f = Fake(); val p = Phone(); val h = help(pc, f, on = false, phone = p); val all = listOf(n("a"), n("b", long))
        h.changed(all)
        var got = false
        h.replies("Ana: hi", "en", "") { _, _ -> got = true }
        assertEquals("", h.digest(all)); assertEquals("", h.short(all[1], all)); assertFalse(h.canReply())
        assertTrue(got)
        assertEquals(0, f.digestCalls.size + f.shortCalls.size + f.replyCalls.size + p.prompts.size + p.replyCalls.size)
    }

    @Test fun digestIsGivenOnlyForTheSameSetAndAddsThePrivateOnes() {
        val f = Fake(); val h = help(pc, f); val all = listOf(n("a"), n("b"), n("c", hidden = true))
        h.changed(all)
        assertEquals("2 chats, 1 private", h.digest(all))
        assertEquals("the private one is never sent", 2, f.digestCalls.single().size)
        f.line = "new"
        val more = all + n("d")
        assertEquals("a different set has no line yet (it asks now)", "", h.digest(more))
        assertEquals("new, 1 private", h.digest(more))
    }

    @Test fun aFailedAnswerGivesNothingAndLongTextsAreShortened() {
        val f = Fake(fail = true); val h = help(pc, f); val all = listOf(n("a"), n("b", long))
        h.changed(all)
        assertEquals("", h.digest(all)); assertEquals("", h.short(all[1], all))
        val g = Fake(); val h2 = help(pc, g); h2.changed(all)
        assertEquals("short one", h2.short(all[1], all)); assertEquals("", h2.short(all[0], all))
    }

    @Test fun phoneModelIsUsedWhenNoComputerWithTheSamePromptsAndGuard() {
        val p = Phone { prompt -> if (prompt.contains("phone notifications")) "{\"labels\": [\"chat\", \"chat\"]}" else "{\"short\": \"w1 w2 w3 w4.\"}" }
        val h = help(null, Fake(), phone = p); val all = listOf(n("a"), n("b", long))
        h.changed(all)
        assertEquals("2 chats", h.digest(all)); assertEquals("w1 w2 w3 w4.", h.short(all[1], all))
        assertTrue(p.prompts.any { it.contains("Allowed labels") } && p.prompts.any { it.contains("Write what it says for a blind listener") })
    }

    @Test fun phoneAnswerThatInventsIsDropped() {
        val p = Phone { "{\"short\": \"Call Marco at 9.\"}" }
        val h = help(null, Fake(), phone = p); val all = listOf(n("b", long))
        h.changed(all)
        assertEquals("", h.short(all[0], all))
    }

    @Test fun computerFailureFallsBackToThePhoneThenThePhoneIsUsedWhileTheComputerIsDown() {
        val f = Fake(fail = true); val p = Phone { "{\"labels\": [\"chat\", \"chat\"]}" }
        val h = help(pc, f, phone = p); val all = listOf(n("a"), n("b"))
        h.changed(all)
        assertEquals("2 chats", h.digest(all))
        assertEquals(1, f.digestCalls.size)
        val before = f.digestCalls.size
        h.changed(all + n("c")); assertEquals("computer is skipped for a while", before, f.digestCalls.size)
        clock += 61_000
        f.fail = false; h.changed(all + n("d"))
        assertEquals("computer again after a minute", before + 1, f.digestCalls.size)
    }

    @Test fun repliesComputerFirstThenPhone() {
        val f = Fake(); val p = Phone(); var r: List<String> = emptyList(); var t: List<String> = emptyList()
        help(pc, f, phone = p).replies("Ana: send it?", "en", "Ana: hi") { a, b -> r = a; t = b }
        assertEquals(listOf("Yes", "No", "Maybe"), r); assertEquals(listOf("Send it"), t)
        assertEquals(Triple("Ana: send it?", "en", "Ana: hi"), f.replyCalls.single()); assertTrue(p.replyCalls.isEmpty())
        val e = Fake(emptyReplies = true)
        help(pc, e, phone = p).replies("Ana: send it?", "es", "ctx") { a, _ -> r = a }
        assertEquals("an empty computer answer falls back to the phone", listOf("Sí", "No", "Tal vez"), r)
        assertEquals(Triple("Ana: send it?", "es", "ctx"), p.replyCalls.single())
        help(null, f, phone = p).replies("x", "en", "") { a, _ -> r = a }
        assertEquals(listOf("Sí", "No", "Tal vez"), r)
    }

    @Test fun privateNotificationsNeverReachAnyPayload() {
        val f = Fake(); val p = Phone { "{\"labels\": [\"chat\"]}" }
        val secret = n("s", "SECRET " + long, hidden = true, title = "Ana")
        val other = n("o", long, title = "Ana", at = 5)
        val all = listOf(secret, other, n("x", "hi", title = "Ana", at = 2))
        for (h in listOf(help(pc, f, phone = p), help(null, f, phone = p))) {
            h.changed(all)
            h.digest(all); h.short(secret, all); h.short(other, all)
        }
        val sent = f.digestCalls.flatten().flatMap { listOf(it.first, it.second, it.third) } + f.shortCalls.flatMap { listOf(it.first, it.second) } + p.prompts
        assertTrue(sent.none { it.contains("SECRET") })
        assertTrue("the private one is never the message", f.shortCalls.none { it.first.contains("SECRET") })
    }
}
