package com.faka.invisible.claude

import org.junit.Assert.assertEquals
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

    @Test fun doneLineHasNoNameWithOneComputer() {
        val b = DoneBatch()
        assertTrue(b.add("Laptop", "site")); assertTrue(!b.add("Laptop", "site"))
        assertEquals("site, done", b.take(false)?.text)
        assertNull(b.take(false))
    }
    @Test fun doneLineNamesTheComputerWithTwoOrMore() {
        val b = DoneBatch(); b.add("Work", "site")
        assertEquals("Work, site, done", b.take(true)?.text)
    }
    @Test fun doneBatchAcrossComputers() {
        val b = DoneBatch(); b.add("Work", "site"); b.add("Home", "site"); b.add("Home", "api")
        assertEquals("Work, site, done. 2 more windows done", b.take(true)?.text)
        b.add("Home", "a"); b.add("Home", "b")
        assertEquals("a, done. 1 more windows done", b.take(false)?.text)
    }
    @Test fun computerListLabels() {
        assertEquals("Work, needs you", ClaudeVoice.computerNeeds("Work").text)
    }
}
