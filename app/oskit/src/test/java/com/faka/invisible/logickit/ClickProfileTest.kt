package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ClickProfileTest {
    private val late = List(40) { 200.0 + (if (it % 2 == 0) 25.0 else -25.0) }

    @Test fun measuresMeanAndSpread() {
        val p = ClickProfile.of(late)
        assertEquals(200.0, p.meanMs, 0.001)
        assertEquals(25.0, p.sdMs, 1.0)
        assertEquals(40, p.count)
        assertEquals(80, p.hist.size)
        assertEquals(1.0, p.hist.sum(), 1e-9)
    }

    @Test fun jsonRoundTrip() {
        val p = ClickProfile.of(late)
        val q = ClickProfile.fromJson(p.toJson())!!
        assertEquals(p.meanMs, q.meanMs, 1e-9); assertEquals(p.sdMs, q.sdMs, 1e-9); assertEquals(p.count, q.count)
        assertEquals(p.hist, q.hist)
        assertNull(ClickProfile.fromJson(null)); assertNull(ClickProfile.fromJson("nonsense"))
    }

    @Test fun fewClicksKeepNomonStart() {
        val p = ClickProfile.of(listOf(300.0, 310.0))
        assertEquals(ClickModel.Params().mu0, p.params().mu0, 0.0)
    }

    /** Eight items 350 ms apart; the person always means item 3 and clicks 200 ms after it. Count the clicks until it is chosen. */
    private fun clicksToChoose(m: ClickModel<Int>, seed: Long): Int {
        val rnd = Random(seed)
        val period = 2800.0
        m.start(List(8) { it to 1.0 }.toMap(), List(8) { it to it * 350.0 }.toMap())
        for (k in 1..30) {
            val at = 3 * 350.0 + 200.0 + rnd.nextGaussian() * 25.0 + (k - 1) * period
            if (m.click(at)) { check(m.chosen() == 3); return k }
        }
        return 31
    }

    @Test fun seededModelChoosesFasterWhenClicksAreLate() {
        val profile = ClickProfile.of(late)
        var seeded = 0; var plain = 0
        for (s in 1L..10L) {
            seeded += clicksToChoose(profile.model(2800.0), s)
            plain += clicksToChoose(ClickModel(2800.0), s)
        }
        assertTrue("seeded $seeded plain $plain", seeded < plain)
    }
}
