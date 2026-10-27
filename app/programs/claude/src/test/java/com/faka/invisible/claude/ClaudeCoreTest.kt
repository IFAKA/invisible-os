package com.faka.invisible.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCoreTest {
    private fun ask(id: String = "1", summary: String = "run npm test") = ClaudeEvent.of(id, "permission", "demo", summary, listOf("Allow", "Deny", "Always allow"))!!

    @Test fun summaryIsCutToTwelveWords() {
        val e = ask(summary = (1..30).joinToString(" ") { "w$it" })
        assertEquals(12, e.summary.removeSuffix("...").split(" ").size)
        assertTrue(e.summary.endsWith("..."))
    }
    @Test fun noteKeepsMoreWords() = assertEquals(25, ClaudeEvent.of("2", "done", "p", (1..40).joinToString(" ") { "w$it" }, emptyList())!!.summary.removeSuffix("...").split(" ").size)
    @Test fun noIdMeansNoEvent() = assertNull(ClaudeEvent.of("", "done", "p", "x", emptyList()))
    @Test fun blankProjectBecomesClaudeAndUnknownKindNeedsYou() {
        val e = ClaudeEvent.of("3", "weird", "  ", "hi", emptyList())!!
        assertEquals("Claude", e.project); assertEquals(ClaudeEvent.KIND_NEEDS, e.kind); assertFalse(e.needsAnswer)
    }
    @Test fun optionsAreCleanedAndLimited() {
        val e = ClaudeEvent.of("4", "question", "p", "q", listOf(" A  ", "", "A", "B", "C", "D", "E", "F", "G"))!!
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), e.options); assertTrue(e.needsAnswer)
    }
    @Test fun choicesEndWithReadAgain() {
        val c = Choice.of(ask())
        assertEquals(4, c.size); assertEquals(Choice.Option("Allow"), c.first()); assertEquals(Choice.Again, c.last())
    }
    @Test fun queueTakesEachIdOnceAndQuestionsFirst() {
        val q = ClaudeQueue()
        assertTrue(q.add(ClaudeEvent.of("1", "done", "p", "ok", emptyList())!!))
        assertTrue(q.add(ask("2")))
        assertFalse(q.add(ask("2")))
        assertEquals("2", q.nextByUrgency()!!.id)
        assertEquals("1", q.nextByUrgency()!!.id)
        assertNull(q.nextByUrgency())
        assertFalse(q.add(ask("2")))   // an id that was spoken never comes back
    }
}
