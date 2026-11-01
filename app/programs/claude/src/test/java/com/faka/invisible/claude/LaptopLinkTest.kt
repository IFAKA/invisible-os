package com.faka.invisible.claude

import java.io.IOException
import org.junit.Test
import org.junit.Assert.assertEquals

class LaptopLinkTest {
    @Test fun wrongTokenIsNamed() {
        assertEquals(LaptopLink.WRONG_TOKEN, LaptopLink.why(IOException("HTTP 401")))
        assertEquals(LaptopLink.WRONG_TOKEN, LaptopLink.why(IOException("HTTP 403")))
    }
    @Test fun anythingElseIsNoAnswer() {
        assertEquals(LaptopLink.NO_ANSWER, LaptopLink.why(java.net.SocketTimeoutException("timeout")))
        assertEquals(LaptopLink.NO_ANSWER, LaptopLink.why(IOException("HTTP 500")))
    }
    @Test fun probeWithoutAddressFailsQuietly() {
        assertEquals(false, LaptopLink.probe(Computer("A", "", "")).ok)
    }
    @Test fun lineStates() {
        assertEquals("No laptop yet", LaptopLink.headline(LaptopLink.tone(0, null)))
        assertEquals("Laptop: checking...", LaptopLink.headline(LaptopLink.tone(1, null)))
        assertEquals("Laptop: connected", LaptopLink.headline(LaptopLink.tone(2, true)))
        assertEquals("Laptop: not reachable", LaptopLink.headline(LaptopLink.tone(1, false)))
    }
    @Test fun titleIsPluralForSeveral() {
        assertEquals("Laptop", LaptopLink.title(0)); assertEquals("Laptop", LaptopLink.title(1)); assertEquals("Laptops", LaptopLink.title(2))
    }
}
