package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickModelTest {
    private val keys = listOf("A", "B", "C", "D", "E")
    private val noon = mapOf("A" to 0.0, "B" to 600.0, "C" to 1200.0, "D" to 1800.0, "E" to 2400.0)
    private val prior = mapOf("A" to 0.4, "B" to 0.25, "C" to 0.15, "D" to 0.1, "E" to 0.1)

    /*
     * Reference: the real Nomon-Simulation classes (Nomon_Core/clock_inference_engine.py, ClockInference) were driven
     * with this exact script (5 items, period 3 s, the rounds below, one round restarts from the priors after a
     * win, learn_scores after each win) and every posterior after every click was saved. Script: src/test/resources/nomon_reference.py.
     * Pairs: (posterior of A..E, is_winner).
     */
    private val rounds = listOf(
        listOf(1300, 4300), listOf(100), listOf(1750, 2100, 5100), listOf(2650), listOf(1900), listOf(1300),
        listOf(1450), listOf(200), listOf(1350), listOf(1800, 4800), listOf(1400), listOf(2600),
    )
    private val expected = listOf(
        doubleArrayOf(6.418875372326631e-17, 7.960921123107521e-05, 0.9997732409589499, 0.00014714982981863415, 3.426666635928878e-16) to true,
        doubleArrayOf(0.9998500941303914, 0.00013796356998254676, 1.9276481507950618e-16, 6.018158245447672e-18, 1.1942299625866609e-05) to true,
        doubleArrayOf(2.0972084599510484e-19, 5.819506226894394e-13, 0.007021863647215346, 0.9929759146856671, 2.221666535672531e-06) to true,
        doubleArrayOf(0.07678818747209352, 1.1027976754521739e-11, 3.2684117707718635e-21, 4.2665734108642306e-07, 0.9232113858595374) to false,
        doubleArrayOf(1.913040076611291e-15, 5.59926705869284e-17, 6.666636778404873e-05, 0.999727956547411, 0.00020537708480294464) to true,
        doubleArrayOf(5.96038055731091e-17, 7.39227931905707e-05, 0.9997894381643299, 0.0001366390424792546, 3.181902749075085e-16) to true,
        doubleArrayOf(6.160378286029216e-21, 7.539127957847938e-07, 0.9864301891115647, 0.013569056972521799, 3.1178679417746722e-12) to false,
        doubleArrayOf(0.9961126359794081, 0.00388702075813944, 1.700344570353456e-13, 5.525158092013265e-21, 3.4326228238832854e-07) to true,
        doubleArrayOf(5.000021091536246e-19, 6.16000254412382e-06, 0.9988703047701444, 0.0011235352272855683, 2.5989831277799107e-14) to true,
        doubleArrayOf(1.8083345514287635e-17, 5.153527587906734e-15, 0.0020487396746591887, 0.9979319756924856, 1.9284632849995234e-05) to true,
        doubleArrayOf(4.318275851725257e-20, 1.6767658252810847e-06, 0.9969603598679291, 0.0030379633660242017, 2.2148859138176716e-13) to true,
        doubleArrayOf(0.01791874103269774, 8.165002472453785e-13, 2.3878475717040815e-20, 9.890024239161288e-07, 0.9820802699640618) to false,
    )

    @Test fun matchesNomonReference() {
        val m = ClickModel<String>(3000.0)
        var k = 0
        for (r in rounds) {
            m.start(prior, noon)
            for (c in r) {
                val win = m.click(c.toDouble())
                val (post, ew) = expected[k++]
                val got = m.posteriors()
                keys.forEachIndexed { i, key -> assertEquals("click $k $key", post[i], got.getValue(key), 1e-6) }
                assertEquals("win $k", ew, win)
                if (win) { m.learn(m.chosen()!!); break }
            }
        }
        assertEquals(expected.size, k)
    }

    private fun fresh() = ClickModel<String>(3000.0).also { it.start(prior, noon) }

    @Test fun clickAfterItemMakesItLikely() {
        val m = fresh()
        m.click(1400.0)   // 200 ms after C
        assertEquals("C", m.best())
        assertTrue(m.posteriors().getValue("C") > 0.5)
    }

    @Test fun lateClickStillPicksAfterTwoCycles() {
        val m = fresh()
        assertTrue(m.click(1400.0 + 2 * 3000.0))
        assertEquals("C", m.chosen())
    }

    @Test fun wrongSingleClickIsRecovered() {
        val m = ClickModel<String>(3000.0)
        m.start(prior, noon)
        assertTrue(m.click(1400.0))             // person meant D, got C
        m.learn(m.chosen()!!)
        m.undo()                                // undo drops it
        m.start(prior, noon, 10000.0)
        assertNull(m.chosen())
        assertTrue(m.click(10000.0 + 2000.0))   // clicks D again
        assertEquals("D", m.chosen())
    }

    @Test fun ambiguousClickDoesNotChoose() {
        val m = fresh()
        m.click(900.0)                          // between B and C: no item has a clear lead
        assertNull(m.chosen())
    }

    @Test fun startAloneNeverChooses() {
        val m = ClickModel<String>(3000.0)
        m.start(mapOf("A" to 0.99, "B" to 0.01), mapOf("A" to 0.0, "B" to 1500.0))
        assertNull(m.chosen())   // the handicap keeps a strong prior from winning alone
    }

    /** A new model per round (the items and the cycle change) that inherits the last one learns exactly like one model. */
    @Test fun inheritedModelsLearnLikeOneModel() {
        val one = ClickModel<String>(3000.0)
        var prev: ClickModel<String>? = null
        for (r in rounds) {
            one.start(prior, noon)
            val m = ClickModel<String>(3000.0).also { n -> prev?.let { n.inherit(it) }; n.start(prior, noon) }
            for (c in r) {
                val w1 = one.click(c.toDouble()); val w2 = m.click(c.toDouble())
                assertEquals(w1, w2)
                keys.forEach { assertEquals(one.posteriors().getValue(it), m.posteriors().getValue(it), 1e-9) }
                if (w1) { one.learn(one.chosen()!!); m.learn(m.chosen()!!); break }
            }
            prev = m
        }
        assertEquals(one.offsets(), prev!!.offsets())
    }

    @Test fun seededOffsetsMoveTheDensity() {
        val a = ClickModel<String>(3000.0); val b = ClickModel<String>(3000.0)
        b.seed(List(30) { 0.30 })   // this person clicks 300 ms late
        a.start(prior, noon); b.start(prior, noon)
        a.click(1500.0); b.click(1500.0)   // 300 ms after C
        assertTrue(b.posteriors().getValue("C") > a.posteriors().getValue("C"))
    }

    @Test fun restartKeepsTheScoresAndTakesNewTimes() {
        val a = ClickModel<String>(3000.0); a.start(prior, noon)
        a.click(1300.0)   // 100 ms after C
        val before = a.posteriors()
        // a focus round: C, D and B only, another cycle length and other onset times; the evidence stays (no start handicap)
        val b = ClickModel<String>(1500.0); b.inherit(a)
        val sc = a.scoresMap().filterKeys { it in listOf("C", "D", "B") }
        b.restart(sc, mapOf("C" to 0.0, "D" to 500.0, "B" to 1000.0), 5000.0)
        val rel = { m: Map<String, Double> -> m.getValue("C") / m.getValue("D") }
        assertEquals(rel(before), rel(b.posteriors()), 1e-9)
        assertEquals(3, b.posteriors().size)
        // a click 100 ms after C's NEW onset adds to C again
        b.click(5100.0)
        assertTrue(b.posteriors().getValue("C") > rel(before) / (1 + rel(before)))
        assertEquals("C", b.best())
    }
}
