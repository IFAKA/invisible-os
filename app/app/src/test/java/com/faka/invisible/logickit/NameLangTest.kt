package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameLangTest {
    @Test fun packageTableHit() = assertEquals("es", NameLang.of("com.brubank", "Brubank"))
    @Test fun brandStaysEnglishEvenWithASpanishWord() = assertEquals("en", NameLang.of("com.whatsapp", "La WhatsApp de mi"))
    @Test fun wordHit() = assertEquals("es", NameLang.of("x.y", "Mi Tarjeta de Transporte"))
    @Test fun letterHit() = assertEquals("es", NameLang.of("x.y", "Diseño"))
    @Test fun defaultIsEnglish() = assertEquals("en", NameLang.of("x.y", "Calculator"))
    @Test fun voiceIsNullForEnglish() { assertNull(NameLang.voice("x.y", "Camera")); assertEquals("es", NameLang.voice("x.y", "Mercado Pago")) }
}
