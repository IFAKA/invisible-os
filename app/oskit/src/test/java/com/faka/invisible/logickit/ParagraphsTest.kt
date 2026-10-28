package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphsTest {
    @Test fun blankLinesSplit() {
        assertEquals(listOf("One two.", "Three four."), Paragraphs.split("One two.\n\n\nThree four.\n"))
        assertEquals(listOf("One two.", "Three four."), Paragraphs.split("One two.\r\n \r\nThree four."))
    }

    @Test fun noBlankLinesSplitsOnLines() {
        assertEquals(listOf("Milk", "Eggs"), Paragraphs.split("Milk\nEggs"))
    }

    @Test fun linesInsideAParagraphStayWhenThereAreBlankLines() {
        assertEquals(listOf("a b c d", "e f"), Paragraphs.split("a b\nc d\n\ne f"))
    }

    @Test fun linksAndEmptyPiecesGoAway() {
        assertEquals(listOf("Read this"), Paragraphs.split("Read this https://example.com/x\n\nhttps://example.com\n\n---\n\n"))
        assertTrue(Paragraphs.split("   \n\n  ").isEmpty())
    }

    @Test fun longParagraphIsCutAtSentences() {
        val s = "This is a sentence with eight words in it. "
        val parts = Paragraphs.split(s.repeat(20))
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.words().size <= Paragraphs.MAX_WORDS && it.endsWith(".") })
        assertEquals(20, parts.sumOf { p -> p.split(". ").size })
    }

    @Test fun oneLongSentenceStaysWhole() {
        val one = (1..100).joinToString(" ") { "w$it" } + "."
        assertEquals(listOf(one), Paragraphs.split(one))
    }

    @Test fun keyIsStableAndDiffers() {
        assertEquals(Paragraphs.key("hello"), Paragraphs.key("hello"))
        assertNotEquals(Paragraphs.key("hello"), Paragraphs.key("hellp"))
    }
}
