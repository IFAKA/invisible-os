package com.faka.invisible.tell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TellSolverTest {
    companion object { val solver: TellSolver by lazy { TellSolver().also { it.solve() } } }

    @Test fun startValueIsHalf() {
        val v = solver.value(1, TellRules.start())
        assertEquals(0.5, v, 0.05)
    }

    @Test fun solvesFast() {
        val t0 = System.nanoTime()
        TellSolver().solve()
        val s = (System.nanoTime() - t0) / 1e9
        println("TellSolver.solve: $s s")
        assertTrue("took $s s", s < 3.0)
    }

    @Test fun nashIsMixedWhenBothCanCharge() {
        var worst = 0.0
        for (e1 in 3..5) for (e2 in 3..5) {
            val mix = solver.mix(1, State(e1, e2, 4, 4))
            assertEquals(1.0, mix.values.sum(), 1e-9)
            worst = maxOf(worst, mix.values.max())
            assertTrue("$e1 $e2 $mix", mix.values.max() < 0.8)
        }
        println("max move probability at round 1, both energy >= 3: $worst")
    }

    @Test fun mixOnlyLegalMoves() {
        assertEquals(setOf(Move.GUARD), solver.mix(3, State(0, 4, 4, 4)).keys)
        assertEquals(setOf(Move.JAB, Move.GUARD), solver.mix(3, State(2, 4, 4, 4)).keys)
    }

    @Test fun terminalValues() {
        assertEquals(1.0, solver.value(21, State(3, 3, 3, 2)), 0.0)
        assertEquals(0.0, solver.value(5, State(3, 3, 0, 2)), 0.0)
        assertEquals(0.5, solver.value(21, State(3, 3, 2, 2)), 0.0)
    }
}
