package com.faka.invisible.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputersTest {
    private val home = Computer("Home", "http://100.64.0.5:8765", "tok1")
    private val work = Computer("Work", "http://100.64.0.6:8766", "tok\"2\\x")

    @Test fun jsonRoundTripKeepsOddCharacters() {
        val list = listOf(home, work, Computer("Ana's \"Mac\"", "h:1", "é\nñ"))
        assertEquals(list, Computers.decode(Computers.encode(list)))
    }
    @Test fun badJsonGivesNoComputersAndNeverThrows() {
        for (s in listOf("", "x", "[", "[{", "[{\"name\":", "{\"a\":1}", "[1,2]")) assertTrue(s, Computers.decode(s).isEmpty())
    }
    @Test fun decodeDropsBlankAndRepeatedNames() {
        val j = "[{\"name\":\" \",\"url\":\"a\",\"token\":\"b\"},{\"name\":\"Home\",\"url\":\"a\",\"token\":\"b\"},{\"name\":\"home\",\"url\":\"c\",\"token\":\"d\"}]"
        assertEquals(listOf("Home"), Computers.decode(j).map { it.name })
    }

    @Test fun oldSingleAddressBecomesLaptop() {
        assertEquals(listOf(Computer("Laptop", "http://x:1", "t")), Computers.resolve("", " http://x:1 ", " t "))
    }
    @Test fun savedListWinsOverOldAddress() {
        assertEquals(listOf(home), Computers.resolve(Computers.encode(listOf(home)), "http://x:1", "t"))
    }
    @Test fun nothingSavedIsNoComputers() {
        assertTrue(Computers.resolve("", "", "").isEmpty()); assertTrue(Computers.resolve("[]", "http://x", "").isEmpty())
    }

    @Test fun namesMustBeFilledAndUniqueIgnoringCase() {
        val l = listOf(home)
        assertNotNull(Computers.problem(l, "   ", "u", "t"))
        assertNotNull(Computers.problem(l, " home ", "u", "t"))
        assertNotNull(Computers.problem(l, "Work", "", "t"))
        assertNotNull(Computers.problem(l, "Work", "u", " "))
        assertNull(Computers.problem(l, "Work", "u", "t"))
        assertNull("a computer may keep its own name", Computers.problem(l, "HOME", "u", "t", renaming = "Home"))
    }
    @Test fun addRenameRemove() {
        var l = Computers.add(Computers.add(emptyList(), home), work.copy(name = "  Work   Mac "))
        assertEquals(listOf("Home", "Work Mac"), l.map { it.name })
        l = Computers.rename(l, "work mac", "Office")
        assertEquals(listOf("Home", "Office"), l.map { it.name })
        assertEquals(listOf("Home"), Computers.remove(l, "OFFICE").map { it.name })
        assertEquals("Office", Computers.find(l, " office ")?.name)
    }
    @Test fun devHooks() {
        assertTrue(Computers.single("", "t").isEmpty())
        assertEquals(listOf(Computer("Laptop", "u", "t")), Computers.single("u", "t"))
        assertEquals(listOf(Computer("A", "http://h:1", "t1"), Computer("B b", "http://h:2", "t2")), Computers.parse("A|http://h:1|t1; B  b |http://h:2|t2"))
    }

    @Test fun doneLineIsTheWindowName() {
        val b = DoneBatch()
        assertTrue(b.add("site")); assertTrue(!b.add("site"))
        assertEquals("site, done", b.take()?.text)
        assertNull(b.take())
    }
    @Test fun doneBatchCountsTheOthers() {
        val b = DoneBatch(); b.add("site"); b.add("site"); b.add("api"); b.add("web")
        assertEquals("site, done. 2 more windows done", b.take()?.text)
    }

    @Test fun activeIsTheNamedOneOrTheFirst() {
        val l = listOf(home, work)
        assertEquals("Work", Computers.active(l, " work ".trim())?.name)
        assertEquals("Home", Computers.active(l, null)?.name)
        assertEquals("Home", Computers.active(l, "Gone")?.name)
        assertNull(Computers.active(emptyList(), "Home"))
        assertEquals(listOf("Work"), Computers.others(l, home).map { it.name })
        assertEquals(listOf("Home", "Work"), Computers.others(l, null).map { it.name })
    }
    @Test fun removingTheActiveOneMovesToTheNext() {
        val c = Computer("Cloud", "u", "t"); val l = listOf(home, work, c)
        assertEquals("Work", Computers.activeAfter(l, Computers.remove(l, "Home"), "Home"))
        assertEquals("Home", Computers.activeAfter(l, Computers.remove(l, "Cloud"), "Cloud"))
        assertEquals("Cloud", Computers.activeAfter(l, Computers.remove(l, "Work"), "Cloud"))
        assertEquals("Work", Computers.activeAfter(l, Computers.remove(l, "Home"), "Work"))
        assertEquals("Home", Computers.activeAfter(l, l, null))
        assertNull(Computers.activeAfter(l, emptyList(), "Home"))
        assertEquals("Home", Computers.activeAfter(emptyList(), listOf(home), null))
    }

    @Test fun pairCodeRoundTrip() {
        val c = Computer("Ana's \"Mac\" é", "http://100.64.0.5:8765", "tok_en-1\\x")
        val code = Computers.pairCode(c)
        assertTrue(code.startsWith("invisible-pair:"))
        assertEquals(c, Computers.parsePair(code))
        assertEquals(c, Computers.parsePair("  here you go:\n$code \nthanks"))
    }
    @Test fun pairCodeFromThePythonBridge() {
        // made by bridge/server.py pair_code("Home", "http://100.64.0.5:8765", "abc")
        val json = "{\"n\":\"Home\",\"u\":\"http://100.64.0.5:8765\",\"t\":\"abc\"}"
        val code = "invisible-pair:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertEquals(Computer("Home", "http://100.64.0.5:8765", "abc"), Computers.parsePair(code))
    }
    @Test fun badPairCodesGiveNullAndNeverThrow() {
        fun b64(s: String) = "invisible-pair:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        for (s in listOf("", "hello", "invisible-pair:", "invisible-pair:!!!", "invisible-pair:@@@@", b64("not json"), b64("[1]"), b64("{"),
            b64("{\"n\":\"A\",\"u\":\"ftp://x\",\"t\":\"k\"}"), b64("{\"n\":\"A\",\"u\":\"http://x\",\"t\":\"\"}"),
            b64("{\"n\":\"A\",\"u\":\"http://x\"}"), b64("{\"n\":1,\"u\":\"http://x\",\"t\":\"k\"}")))
            assertNull(s, Computers.parsePair(s))
    }
    @Test fun pairCodeWithoutNameStillParsesSoTheNameCanBeTyped() {
        val c = Computers.parsePair(Computers.pairCode(Computer("", "http://x:1", "k")))
        assertEquals("", c?.name)
    }
    @Test fun switchLabels() {
        assertEquals("Switch to Work", ClaudeVoice.switchTo("Work").text)
    }
    @Test fun qrFormWithSlashesParses() {
        val c = Computer("Work", "http://h:8766", "tok")
        val link = Computers.pairCode(c).replaceFirst("invisible-pair:", "invisible-pair://")
        assertEquals(c, Computers.parsePair(link))
        assertEquals(c, Computers.parsePair("Found: " + Computers.pairCode(c) + " ok"))
        assertNull(Computers.parsePair("invisible-pair://!!!"))
    }
    @Test fun firstPairedComputerIsActiveTheNextIsNot() {
        val a = Computers.addPaired(emptyList(), Computer("Mac", "http://h:1", "t1"))
        assertTrue(a.active); assertEquals("Mac", a.name)
        assertEquals("Added Mac. The phone listens to it.", Computers.addedSentence(a))
        val b = Computers.addPaired(a.list, Computer("Work", "http://h:2", "t2"))
        assertTrue(b.active); assertEquals(2, b.list.size)
        assertEquals("Added Work. The phone listens to it.", Computers.addedSentence(b))
    }
    @Test fun pairedNameClashGetsANumberAndSameBridgeIsNotAddedTwice() {
        val l = Computers.addPaired(emptyList(), Computer("Mac", "http://h:1", "t1")).list
        val b = Computers.addPaired(l, Computer("mac", "http://h:2", "t2"))
        assertEquals("mac 2", b.name)
        val again = Computers.addPaired(b.list, Computer("Other", "http://h:2", "t2"))
        assertTrue(again.already); assertEquals(b.list, again.list); assertEquals("mac 2", again.name)
        assertEquals("Computer", Computers.addPaired(emptyList(), Computer("  ", "http://h:3", "t")).name)
        assertEquals("My Mac", Computers.addPaired(emptyList(), Computer("x", "http://h:3", "t"), name = " My   Mac ").name)
    }

    @Test fun pairCodeFromTheWebLink() {
        val code = Computers.pairCode(Computer("Work", "http://100.1.2.3:8766", "tok"))
        val web = "https://invisible-os.vercel.app/pair#" + code.removePrefix("invisible-pair:")
        assertEquals(Computer("Work", "http://100.1.2.3:8766", "tok"), Computers.parsePair(web))
        assertEquals(null, Computers.parsePair("https://invisible-os.vercel.app/pair#"))
    }
}
