package com.faka.invisible

import com.faka.invisible.logickit.*

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    @Test fun linksBecomeLabels() {
        assertEquals("mira esto un link de YouTube", MessageLogic.spoken("mira esto https://youtu.be/x7Fq?t=3", "es"))
        assertEquals("look a link", MessageLogic.spoken("look www.example.com/a/b", "en"))
        assertEquals("an Instagram link", MessageLogic.spoken("https://www.instagram.com/reel/abc/", "en"))
    }
    @Test fun strippedForDetection() {
        assertEquals("mira esto", MessageLogic.stripLinks("mira esto https://youtu.be/x7Fq"))
        assertFalse(MessageLogic.hasWords("https://youtu.be/x7Fq"))
        assertTrue(MessageLogic.hasWords("hola https://youtu.be/x7Fq"))
        assertEquals(MsgType.INFO, MessageLogic.classify("https://example.com/what?x=1", "en"))
    }
}
