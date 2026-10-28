package com.faka.invisible

import com.faka.invisible.logickit.SourceMix
import com.faka.invisible.logickit.SourceTrust
import org.junit.Assert.*
import org.junit.Test

class SourceMixTest {
    @Test fun sharesSumToOneAndFollowTrust() {
        val s = SourceMix.shares(listOf("a", "b")) { if (it == "a") 0.75 else 0.25 }
        assertEquals(0.75, s.getValue("a"), 1e-9); assertEquals(1.0, s.values.sum(), 1e-9)
        assertEquals(0.5, SourceMix.shares(listOf("a", "b"), 0.5) { 0.0 }.getValue("a"), 1e-9)   // floor
        assertTrue(SourceMix.shares(emptyList()) { 1.0 }.isEmpty())
    }

    @Test fun mixSumsShareTimesChance() {
        val m = SourceMix.mix(mapOf("a" to mapOf("x" to 0.8, "y" to 0.2), "b" to mapOf("y" to 1.0)), mapOf("a" to 0.5, "b" to 0.5))
        assertEquals(0.4, m.getValue("x"), 1e-9); assertEquals(0.6, m.getValue("y"), 1e-9)
        assertEquals(listOf("y", "x"), SourceMix.ranks(m))
        assertEquals(emptyMap<String, Double>(), SourceMix.normalized(mapOf("x" to 0.0)))
    }

    @Test fun creditTellsTopThreeAndReturnsBest() {
        val told = HashMap<String, Boolean>()
        val best = SourceMix.credit(mapOf("a" to listOf("p", "q"), "b" to listOf("1", "2", "3", "q"), "c" to listOf("z")), { it.indexOf("q") }) { id, h -> told[id] = h }
        assertEquals("a" to 1, best)
        assertEquals(mapOf("a" to true, "b" to false, "c" to false), told)
        assertNull(SourceMix.credit(mapOf("a" to listOf("p")), { it.indexOf("nope") }) { _, _ -> })
    }

    @Test fun trustLearnsAndSurvivesItsTextForm() {
        var saved = ""
        val t = SourceTrust(null) { saved = it }
        assertEquals(0.5, t.trust("x"), 1e-9)
        t.scored("x", true); t.scored("x", true); t.scored("y", false)
        assertEquals("x=2/2;y=0/1", saved)
        val again = SourceTrust(saved)
        assertEquals(0.75, again.trust("x"), 1e-9); assertEquals(1.0 / 3, again.trust("y"), 1e-9)
        assertEquals(0.5, SourceTrust("garbage;=;a=b/c").trust("a"), 1e-9)
    }
}
