package com.faka.invisible

import com.faka.invisible.claude.Computer
import com.faka.invisible.oskit.Notif
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AiHelpTest {
    private val pc = Computer("pc", "http://x", "tok")
    private val long = (1..45).joinToString(" ") { "w$it" }
    private fun n(k: String, text: String = "hi", hidden: Boolean = false) = Notif(k, "p", "App", "T$k", text, 1L, hidden = hidden)

    private class Fake(var line: String = "2 chats", var fail: Boolean = false) : AiHelp.AiClient {
        val digestCalls = ArrayList<List<Triple<String, String, String>>>(); val shortCalls = ArrayList<String>()
        override fun digest(items: List<Triple<String, String, String>>): String { digestCalls += items; if (fail) throw IOException("x"); return line }
        override fun short(text: String): String { shortCalls += text; if (fail) throw IOException("x"); return "short one" }
    }

    private fun help(c: Computer?, f: Fake) = AiHelp({ c }, { it() }, { f })

    @Test fun noComputerMeansNoCallsAndNoAnswers() {
        val f = Fake(); val h = help(null, f); val all = listOf(n("a"), n("b", long))
        h.changed(all)
        assertEquals("", h.digest(all)); assertEquals("", h.short(long)); assertEquals(0, f.digestCalls.size + f.shortCalls.size)
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
        assertEquals("", h.digest(all)); assertEquals("", h.short(long))
        val g = Fake(); val h2 = help(pc, g); h2.changed(all)
        assertEquals("short one", h2.short(long)); assertEquals("", h2.short("hi"))
    }
}
