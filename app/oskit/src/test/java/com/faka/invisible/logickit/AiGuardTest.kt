package com.faka.invisible.logickit

import org.junit.Assert.*
import org.junit.Test

/** Mirrors bridge/test_story.py (check_story) and bridge/test_aitext.py (short, digest). */
class AiGuardTest {
    private val src = "I ran the tests in build.py and 3 of 12 failed. The failing ones are in Parser. " +
        "I fixed the first one and wrote the change to notes.md. Should I commit the fix to the main branch now?"
    private val long = "Hi, this is Dr. Perez's office. Your appointment is moved to Tuesday at 4 pm. Please come ten minutes early and bring your " +
        "insurance card and the lab results from last month. If the time does not work, call us before Monday at noon. Thank you very much."
    private fun json(k: String, v: String) = "{\"$k\": \"$v\"}"

    @Test fun trueStoryPasses() = assertTrue(AiGuard.checkStory("Tests failed, 3 of 12. Fixed one. Commit the fix?", src).first)

    @Test fun inventedNumberNameFileQuote() {
        for (bad in listOf("Tests failed, 5 of 12.", "Fixed it in Lexer.", "Wrote the change to other.md.", "Said \"all green\".")) assertFalse(bad, AiGuard.checkStory(bad, src).first)
    }

    @Test fun sentenceStartCapitalAndAiWordAreFree() {
        assertTrue(AiGuard.checkStory("Done. Waiting for you.", src + " x".repeat(20)).first)
        assertTrue(AiGuard.checkStory("The AI fixed 3 bugs.", src).first)
    }

    @Test fun mustBeShorterAndNotLong() {
        assertFalse(AiGuard.checkStory("Tests failed.", "Tests failed.").first)
        assertFalse(AiGuard.checkStory(List(31) { "tests" }.joinToString(" "), src.repeat(5)).first)
        assertFalse(AiGuard.checkStory("", src).first)
    }

    @Test fun contextCountsAsSource() {
        assertTrue(AiGuard.checkStory("Fixed in Parser.", "$src more more", "Parser, proj").first)
        assertFalse(AiGuard.checkStory("Fixed in Zed.", src, "Parser, proj").first)
    }

    @Test fun shortOkAndInvented() {
        val s = "Dr. Perez moved your appointment to Tuesday at 4 pm. Bring your insurance card."
        assertEquals(s, AiGuard.makeShort(long, "") { json("short", s) })
        assertEquals("", AiGuard.makeShort(long, "") { json("short", "Appointment moved to Tuesday at 5 pm.") })
    }

    @Test fun shortNotShorterOrTooLongOrBroken() {
        assertEquals("", AiGuard.makeShort("Hi there", "") { json("short", "Hi there and welcome") })
        assertEquals("", AiGuard.makeShort(long.repeat(3), "") { json("short", List(50) { "word" }.joinToString(" ")) })
        assertEquals("", AiGuard.makeShort(long, "") { "no json" })
        assertEquals("", AiGuard.makeShort(long, "") { throw RuntimeException("model down") })
        assertEquals("fenced json is read", "Moved to Tuesday at 4 pm.", AiGuard.makeShort(long, "") { "```json\n" + json("short", "**Moved** to Tuesday at 4 pm.") + "\n```" })
    }

    @Test fun contextIsInThePromptAndCountsAsSource() {
        val ctx = "Ana: the cake from Dolce is ready\nAna: it is for Leo"
        var prompt = ""
        val s = "Dr. Perez moved it to Tuesday at 4 pm. This is for Leo."
        assertEquals(s, AiGuard.makeShort(long, ctx) { prompt = it; json("short", s) })
        assertTrue(prompt.contains("Earlier messages in the same chat") && prompt.contains("cake from Dolce"))
        assertEquals("without the context Leo is invented", "", AiGuard.makeShort(long, "") { json("short", s) })
        assertFalse(AiGuard.shortPrompt(long).contains("Earlier messages"))
        assertTrue(AiGuard.shortPrompt(long, "x".repeat(5000)).count { it == 'x' } <= AiGuard.CONTEXT_CHARS)
    }

    private val items = listOf(Triple("WhatsApp", "Ana", "dinner?"), Triple("WhatsApp", "Leo", "ok"), Triple("Rappi", "Order", "Your order is near"), Triple("Shop", "SALE", "50% off"))
    private fun labels(vararg l: String) = "{\"labels\": [${l.joinToString(",") { "\"$it\"" }}]}"

    @Test fun digestCountsAddUp() {
        assertEquals("2 chats, 1 delivery, 1 junk", AiGuard.makeDigest(items) { labels("chat", "chat", "delivery", "junk") })
        assertEquals("plural and synonyms", "2 chats, 1 delivery, 1 junk", AiGuard.makeDigest(items) { labels("chats", "message", "Deliveries", "ad") })
    }

    @Test fun digestWrongCountOrLabelOrBroken() {
        assertEquals("", AiGuard.makeDigest(items) { labels("chat", "chat") })
        assertEquals("", AiGuard.makeDigest(items) { labels("chat", "chat", "chat", "banana") })
        assertEquals("", AiGuard.makeDigest(items) { "nope" })
        assertEquals("", AiGuard.makeDigest(items) { throw RuntimeException("x") })
        assertEquals("", AiGuard.makeDigest(emptyList()) { labels() })
    }

    @Test fun checkDigestAndGroupCut() {
        assertFalse(AiGuard.checkDigest("3 chats, 2 junk", 8)); assertTrue(AiGuard.checkDigest("3 chats, 5 junk", 8))
        val line = AiGuard.digestLine(listOf("chat", "delivery", "news", "work", "payment", "junk"))
        assertEquals(4, line.count { it == ',' }); assertTrue(AiGuard.checkDigest(line, 6))
    }

    @Test fun digestPromptHasEveryItemCut() {
        val p = AiGuard.digestPrompt(listOf(Triple("A".repeat(50), "t", "x".repeat(300))))
        assertTrue(p.contains("Below are 1 phone notifications")); assertTrue(p.count { it == 'x' } <= 120 + 2)
    }

    @Test fun completionsFitTheTypedLettersAndInventNothing() {
        val raw = """Here: {"completions": ["how are you", "hay fever", "what is up", "hey Bob you", "how are you"]} done"""
        assertEquals(listOf("how are you", "hay fever", "what is up", "hey Bob you", "how are you"), AiGuard.parseCompletions(raw))
        assertEquals(listOf("how are you", "hay fever"), AiGuard.makeCompletions("hay", "Ana: hi", "en") { raw })
        assertEquals(listOf("hey Bob you"), AiGuard.makeCompletions("hby", "Bob: hi", "en") { raw })   // Bob is in the chat, so it is not invented
        assertTrue(AiGuard.makeCompletions("hay", "", "en") { throw RuntimeException("x") }.isEmpty())
        assertTrue(AiGuard.makeCompletions("hay", "", "en") { "no json" }.isEmpty())
        assertTrue(AiGuard.completePrompt("hay", "en", "Ana: hi").contains("Ana: hi"))
    }
}
