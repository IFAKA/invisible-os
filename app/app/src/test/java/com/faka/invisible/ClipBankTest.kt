package com.faka.invisible.voicekit

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipBankTest {
    /** The laptop script (tools/voice_bakeoff/manifest.py) names clips with the same rule: if this fails, the two disagree. */
    @Test fun keyMatchesTheLaptopScript() {
        assertEquals("4469c228120a", ClipBank.key("Timer done", null))
        assertEquals("4469c228120a", ClipBank.key("  Timer done ", "en"))
        assertEquals(ClipBank.key("Hola", "es"), ClipBank.key("Hola", "es-MX"))
    }

    @Test fun textIsSplitIntoSentences() {
        assertEquals(listOf("They jab.", "You guard.", "Hit."), ClipBank.sentences("They jab. You guard. Hit."))
        assertEquals(listOf("Timer 5 minutes"), ClipBank.sentences("Timer 5 minutes"))
    }
}
