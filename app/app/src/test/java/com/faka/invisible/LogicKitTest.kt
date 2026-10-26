package com.faka.invisible

import com.faka.invisible.logickit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicKitTest {
    @Test fun squashCollapsesAndTrims() = assertEquals("a b c", "  a \t b\n\nc  ".squash())
    @Test fun wordsSkipEmpty() = assertEquals(listOf("a", "b"), "  a   b ".words())
    @Test fun wordsOfBlankIsEmpty() = assertTrue("   ".words().isEmpty())

    @Test fun shortTextHasNoLanguage() = assertNull(ChatLang.of("hola") { "es" })
    @Test fun unknownLanguageIsNull() = assertNull(ChatLang.of("a long enough text here") { "und" })
    @Test fun detectedLanguageIsReturned() = assertEquals("es", ChatLang.of("vienes mañana a casa?") { "es" })
    @Test fun englishAndUnknownNeedNoTranslation() {
        assertFalse(ChatLang.needsTranslation(null)); assertFalse(ChatLang.needsTranslation("en")); assertTrue(ChatLang.needsTranslation("es"))
    }
}
