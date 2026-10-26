package com.faka.invisible

import com.faka.invisible.logickit.MusicSearch
import org.junit.Assert.*
import org.junit.Test

class MusicSearchTest {
    private val answer = """{"a":{"text":"Top result"},"b":[{"text":"Songs"},{"text":"De Música Ligera"},{"text":"Soda Stereo"},{"text":" • "},{"videoId":"sWft4cMiwXQ"}]}"""

    @Test fun takesTheFirstSongAfterTheSongsHeader() {
        val h = MusicSearch.first(answer)!!
        assertEquals("sWft4cMiwXQ", h.videoId); assertEquals("De Música Ligera", h.title); assertEquals("Soda Stereo", h.artist)
    }

    @Test fun noSongsMeansNull() = assertNull(MusicSearch.first("""{"a":{"text":"Nothing"}}"""))

    @Test fun bodyDropsQuotesAndBackslashes() = assertTrue(MusicSearch.body("a \"b\" \\ c").contains("\"query\":\"a b c\""))
}
