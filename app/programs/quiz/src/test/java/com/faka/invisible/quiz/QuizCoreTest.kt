package com.faka.invisible.quiz

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class QuizCoreTest {
    @Test fun fortyQuestionsWithThreeDifferentChoices() {
        assertEquals(40, QuizCore.questions.size)
        for (q in QuizCore.questions) {
            assertEquals(q.text, 3, setOf(q.right, q.wrong1, q.wrong2).size)
            assertTrue(q.text, q.text.split(" ").size <= 12)
        }
        assertEquals(40, QuizCore.questions.map { it.text }.toSet().size)
    }

    @Test fun aGameHasTenDifferentQuestionsAndTheAnswerIndexIsRight() {
        for (seed in 1L..20L) {
            val g = QuizCore.pick(Random(seed))
            assertEquals(10, g.size)
            assertEquals(10, g.map { it.text }.toSet().size)
            for (a in g) {
                val q = QuizCore.questions.first { it.text == a.text }
                assertEquals(q.right, a.choices[a.answer])
                assertEquals(3, a.choices.size)
            }
        }
    }

    @Test fun theRightAnswerMovesAround() {
        val spots = (1L..30L).flatMap { QuizCore.pick(Random(it)).map { a -> a.answer } }.toSet()
        assertEquals(setOf(0, 1, 2), spots)
    }
}
