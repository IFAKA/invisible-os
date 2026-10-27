package com.faka.invisible.claude

import org.junit.Assert.*
import org.junit.Test

class RoomsChoiceTest {
    @Test fun spokenIsLabelAndDescriptionCutAtTwelveWords() {
        assertEquals("Tree", ChoiceText.spoken(ChoiceOpt(1, "Tree", "")))
        assertEquals("Tree: group apps by use", ChoiceText.spoken(ChoiceOpt(1, "Tree.", "group apps by use")))
        val long = ChoiceText.spoken(ChoiceOpt(2, "Flat list", "one two three four five six seven eight nine ten eleven twelve"))
        assertEquals(12, long.removeSuffix("...").split(" ").size)
        assertTrue(long.endsWith("..."))
    }

    @Test fun paragraphsSplitOnBlankLinesAndLongOnesAtSentences() {
        assertEquals(listOf("One.", "Two a b.", "Three."), Paragraphs.split("One.\n\n  Two   a b.\nThree.\n\n"))
        val s = (1..10).joinToString(" ") { "Sentence $it has some words in it, about twelve words long, fine ok." }
        val parts = Paragraphs.split(s)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.split(" ").size <= 75 })
        assertEquals(s, parts.joinToString(" "))
    }

    @Test fun moreSitsBeforeReadAllAndIsOnThePhone() {
        val r = Room("%1", "A", "p", "claude", "needs", "", emptyList(), listOf("go", "readall", "write"))
        assertEquals(listOf("hear", "go", "more", "readall", "write"), RoomAction.earbudList(r).map { it.id })
        assertTrue(RoomAction("more").onPhone)
    }
}
