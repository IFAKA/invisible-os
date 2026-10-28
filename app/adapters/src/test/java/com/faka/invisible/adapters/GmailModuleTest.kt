package com.faka.invisible.adapters

import org.junit.Assert.*
import org.junit.Test

class GmailModuleTest {
    private val p = GmailModule.Companion::phrase
    @Test fun senderAndSubject() = assertEquals("Ana Ruiz, Dinner on Friday", p("Ana Ruiz", "Dinner on Friday"))
    @Test fun bodyKeptShort() {
        val body = (1..30).joinToString(" ") { "w$it" }
        assertEquals("Bank News, Update. " + (1..12).joinToString(" ") { "w$it" } + "...", p("Bank News", "Update\n$body"))
    }
    @Test fun shortBodyNoDots() = assertEquals("Leo, Hola. Nos vemos mañana", p("Leo", "Hola\nNos vemos mañana"))
    @Test fun noText() = assertEquals("Leo", p("Leo", ""))
    @Test fun onlyGmail() { assertTrue(GmailModule(ChatAppsModule()).matches(GmailModule.PKG)); assertFalse(GmailModule(ChatAppsModule()).matches("com.whatsapp")) }
}
