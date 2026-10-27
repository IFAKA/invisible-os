package com.faka.invisible.logickit

import org.junit.Assert.*
import org.junit.Test

class ArrivalTest {
    @Test fun normalSaysAppSenderThenText() {
        val l = ArrivalPlan.lines("WhatsApp", "Ana", "Hola, vienes a cenar?", "es", false)
        assertEquals(listOf("WhatsApp, Ana", "Hola, vienes a cenar?"), l.map { it.text })
        assertEquals("es", l[1].lang)
    }
    @Test fun privateSaysOnlyTheApp() {
        val l = ArrivalPlan.lines("Bank", "Ana", "Your code is 1234", "en", true)
        assertEquals(1, l.size)
        assertFalse(l[0].text.contains("1234")); assertFalse(l[0].text.contains("Ana"))
        assertEquals("Bank, new message", l[0].text)
    }
    @Test fun longTextIsCut() {
        val l = ArrivalPlan.lines("Teams", "Bob", "word ".repeat(100), "en", false)
        assertTrue(l[1].text.endsWith("and more")); assertTrue(l[1].text.length < 190)
    }
    @Test fun linkOnlyTextSaysJustWho() {
        assertEquals(1, ArrivalPlan.lines("Teams", "Bob", "https://x.com/a", "en", false).size)
    }
    @Test fun noReadingInCallOrProgram() {
        assertTrue(ArrivalPlan.mayRead(false, false))
        assertFalse(ArrivalPlan.mayRead(true, false)); assertFalse(ArrivalPlan.mayRead(false, true))
    }
    @Test fun burstReadsOnceWithTheLatest() {
        val b = ArrivalBurst<String>()
        assertTrue(b.add("wa|Ana", "one", 0)); assertFalse(b.add("wa|Ana", "two", 300)); assertFalse(b.add("wa|Ana", "three", 900))
        assertTrue(b.add("wa|Bob", "x", 100))
        assertEquals("three", b.take("wa|Ana")); assertNull(b.take("wa|Ana"))
        assertTrue(b.add("wa|Ana", "four", 5000))
    }
}
