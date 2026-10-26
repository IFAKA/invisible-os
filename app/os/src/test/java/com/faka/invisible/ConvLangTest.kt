package com.faka.invisible

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvLangTest {
    @Test fun spanishDefaultEnglishNeedsClearLead() {
        val saved = ArrayList<Map<String, String>>()
        val c = ConvLang { saved.add(it) }
        assertEquals("es", c.get("a")); assertEquals("es", c.learn("a", ""))
        assertEquals("es", c.learn("a", "en"))        // one English vote is not enough
        assertEquals("en", c.learn("a", "en"))        // two more than Spanish
        c.learn("a", "es"); assertEquals("es", c.get("a"))
        c.learn("a", "en"); c.learn("a", "en"); assertEquals("en", c.get("a"))
        assertTrue(c.settled("a"))
        c.learn("a", "es"); c.learn("a", "es"); assertEquals("en", c.get("a"))   // settled: no more votes
        assertEquals("en", ConvLang(saved.last()).get("a"))
        assertFalse(c.settled("b"))
    }
    @Test fun placeholdersAndGroupNames() {
        assertTrue(MessageLogic.isPlaceholder("💟 Sent a sticker"))
        assertTrue(MessageLogic.isPlaceholder("📷 Photo"))
        assertFalse(MessageLogic.isPlaceholder("Sent a photo of the dog to mom yesterday evening"))
        assertFalse(MessageLogic.isPlaceholder("Amo escucharlos hablar"))
        assertEquals("Familia: Lucía Gómez", MessageLogic.stableName("Familia (2 messages): Lucía Gómez"))
    }
}
