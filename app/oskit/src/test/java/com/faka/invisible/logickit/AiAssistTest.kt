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

    private fun n(key: String, title: String, text: String, at: Long, hidden: Boolean = false, pkg: String = "wa") =
        com.faka.invisible.oskit.Notif(key, pkg, "App", title, text, at, hidden = hidden)

    @Test fun earlierMessagesOfTheSameChatOnly() {
        val cur = n("c", "Ana", "can you pick it up?", 10)
        val all = listOf(cur, n("a", "Ana", "the cake is ready", 5), n("b", "Ana", "it is for Leo", 7), n("x", "Bob", "other chat", 6),
            n("y", "Ana", "other app", 6, pkg = "tg"), n("z", "Ana", "from the future", 20), n("h", "Ana", "", 8, hidden = true))
        assertEquals("Ana: the cake is ready\nAna: it is for Leo", AiAssist.earlier(cur, all))
    }

    @Test fun earlierIsCutAndCapped() {
        val cur = n("c", "Ana", "now", 100)
        val all = (1..6).map { n("k$it", "Ana", "m$it " + "w".repeat(600), it.toLong()) }
        val e = AiAssist.earlier(cur, all)
        assertTrue(e.length <= 1000); assertTrue(e.contains("m6")); assertFalse(e.contains("m1 "))
        assertEquals(3, AiAssist.earlier(cur, (1..6).map { n("k$it", "Ana", "m$it", it.toLong()) }).lines().size)
    }

    @Test fun aPrivateNotificationIsNeverContextNorMessage() {
        val priv = n("p", "", "", 10, hidden = true)
        assertEquals("", AiAssist.earlier(priv, listOf(priv, n("a", "", "secret text", 5))))
        val cur = n("c", "Ana", "hi", 10)
        assertFalse(AiAssist.earlier(cur, listOf(cur, n("h", "Ana", "SECRET", 5, hidden = true))).contains("SECRET"))
    }
}
