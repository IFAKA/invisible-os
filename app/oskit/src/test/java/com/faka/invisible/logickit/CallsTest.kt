package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Test

class CallsTest {
    @Test fun kindFromCallStyle() {
        assertEquals(CallKind.INCOMING, Calls.kind("call", Calls.TYPE_INCOMING, 0))
        assertEquals(CallKind.ONGOING, Calls.kind("call", Calls.TYPE_ONGOING, 2))
    }
    @Test fun kindFromActionsWhenNoStyle() {
        assertEquals(CallKind.INCOMING, Calls.kind("call", 0, 2))
        assertEquals(CallKind.ONGOING, Calls.kind("call", 0, 1))
        assertEquals(CallKind.NONE, Calls.kind("msg", 0, 2))
        assertEquals(CallKind.NONE, Calls.kind(null, 1, 2))
    }
    @Test fun callerNamePrefersContactThenTitleThenDigits() {
        assertEquals("Mama", Calls.callerName("+54 11 5555", " Mama "))
        assertEquals("Ana Lopez", Calls.callerName("Ana  Lopez", null))
        assertEquals("1 1 5 5", Calls.callerName("11-55", null))
        assertEquals("Unknown number", Calls.callerName("  ", ""))
    }
    @Test fun rankStarredThenMostCalledOneNumberEach() {
        val l = Calls.rank(listOf(
            Contact("Bea", "111", false, 5), Contact("Zed", "222", true, 1), Contact("Al", "333", false, 5),
            Contact("Bea again", "1 1 1", false, 9), Contact("NoNumber", "", true, 99)))
        assertEquals(listOf("Zed", "Al", "Bea"), l.map { it.name })
    }
    @Test fun rankLimit() = assertEquals(2, Calls.rank(List(5) { Contact("n$it", "10$it") }, 2).size)
    @Test fun emergencyNumbers() {
        assertEquals("911", Calls.emergencyNumber("ar"))
        assertEquals("999", Calls.emergencyNumber("GB"))
        assertEquals("112", Calls.emergencyNumber("DE"))
        assertEquals("112", Calls.emergencyNumber(null))
    }
    @Test fun actionButtons() {
        assertEquals(1, Calls.answerIndex(listOf("Decline", "Answer")))
        assertEquals(0, Calls.declineIndex(listOf("Decline", "Answer")))
        assertEquals(0, Calls.answerIndex(listOf("Contestar", "Rechazar")))
        assertEquals(-1, Calls.answerIndex(listOf("Hang up")))
    }
}
