package com.faka.invisible.tell

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TellMistakeTest {
    private fun play(vararg pairs: Pair<Move, Move>): List<Round> {
        var s = TellRules.start()
        return pairs.mapIndexed { i, (a, b) -> TellRules.resolve(s, a, b, i + 1).also { s = it.after } }
    }

    @Test fun empty() = assertEquals("", costliestMistake(emptyList()).text)

    @Test fun namesTheRound() {
        // round 1 guard/guard (nothing to gain), round 2 charge into a jab (cost one life)
        val t = costliestMistake(play(Move.GUARD to Move.GUARD, Move.CHARGE to Move.JAB)).text
        assertTrue(t, t.startsWith("In round 2 you charged, they jabbed, and it cost you one life."))
    }

    @Test fun habitFactOnlyWhenStrong() {
        val strong = costliestMistake(play(Move.GUARD to Move.GUARD, Move.GUARD to Move.CHARGE, Move.GUARD to Move.GUARD)).text
        assertTrue(strong, strong.endsWith("You guarded 100 percent."))
        val weak = costliestMistake(play(Move.JAB to Move.GUARD, Move.GUARD to Move.CHARGE, Move.CHARGE to Move.GUARD)).text
        assertTrue(weak, !weak.contains("percent"))
    }

    @Test fun shortAndPlain() {
        val t = costliestMistake(play(Move.GUARD to Move.CHARGE, Move.GUARD to Move.JAB, Move.GUARD to Move.JAB)).text
        assertTrue(t, t.split(". ").size <= 2 && t.length < 120)
    }
}
