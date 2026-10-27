package com.faka.invisible.logickit

import org.junit.Assert.*
import org.junit.Test

class AiAssistTest {
    @Test fun long_is_more_than_forty_words() {
        assertFalse(AiAssist.isLong(List(40) { "w" }.joinToString(" ")))
        assertTrue(AiAssist.isLong(List(41) { "w" }.joinToString(" ")))
    }

    @Test fun key_ignores_order_and_changes_with_the_set() {
        val a = Triple("WhatsApp", "Ana", "hi"); val b = Triple("Shop", "Sale", "50% off")
        assertEquals(AiAssist.digestKey(listOf(a, b), 1), AiAssist.digestKey(listOf(b, a), 1))
        assertNotEquals(AiAssist.digestKey(listOf(a, b), 1), AiAssist.digestKey(listOf(a), 1))
        assertNotEquals(AiAssist.digestKey(listOf(a, b), 1), AiAssist.digestKey(listOf(a, b), 2))
    }

    @Test fun private_ones_are_added_so_numbers_add_up() {
        assertEquals("3 chats, 2 junk, 2 private", AiAssist.withPrivate("3 chats, 2 junk", 2))
        assertEquals("3 chats", AiAssist.withPrivate(" 3  chats ", 0))
        assertEquals("", AiAssist.withPrivate("", 2))
    }

    @Test fun digestable_bounds() {
        assertFalse(AiAssist.digestable(0)); assertTrue(AiAssist.digestable(30)); assertFalse(AiAssist.digestable(31))
    }
}
