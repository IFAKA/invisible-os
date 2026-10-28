package com.faka.invisible.clicktest

import com.faka.invisible.logickit.ClickProfile
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole test on a virtual clock. The audio clock is the virtual time in frames. */
class ClickTestGameTest {
    private class Env : ClickTestOut, Timers {
        var t = 0L
        val due = ArrayList<Triple<String, Long, () -> Unit>>()
        val bells = ArrayList<Long>()          // wall ms of every target sound scheduled (latency 0)
        val spoken = ArrayList<String>(); val logs = ArrayList<String>()
        var saved: String? = null; var left = false
        var hits = 0; var misses = 0
        override fun nowFrame() = t * ClickTestSynth.SR / 1000
        override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) {
            if (sound === ClickTestSynth.target) bells.add(atFrame * 1000 / ClickTestSynth.SR)
            if (sound === ClickTestSynth.hit) hits++
            if (sound === ClickTestSynth.miss) misses++
        }
        override fun speak(text: Phrase) { spoken.add(text.text) }
        override fun stopSpeech() {}
        override fun log(line: String) { logs.add(line) }
        override fun save(json: String) { saved = json }
        override fun leave() { left = true }
        override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); due.add(Triple(key, t + delayMs, task)) }
        override fun clear(key: String) { due.removeAll { it.first == key } }
        fun advance(ms: Long) {
            val end = t + ms
            while (true) {
                val next = due.filter { it.second <= end }.minByOrNull { it.second } ?: break
                due.remove(next); t = maxOf(t, next.second); next.third()
            }
            t = end
        }
    }

    @Test fun steadyLatePlayerIsMeasured() {
        // bells include one demo per round (before the beat); a click at demo+180 is off-target, so clicks only follow real bells
        val env = Env()
        val g = ClickTestGame(env, env, { env.t }, { 0L }, seed = 3)
        g.start(); g.onSpeechDone()
        val demoSkipped = HashSet<Long>()
        var rounds = 0
        while (!env.left && env.t < 400_000) {
            val pending = env.bells.sorted()
            // a round schedules 1 demo + 3 bells at once: answer the 3 later ones
            val groups = pending.filter { it !in demoSkipped }
            if (groups.size < 4) { env.advance(50); continue }
            demoSkipped.addAll(groups.take(4)); rounds++
            for (b in groups.drop(1)) {
                if (b + 180 > env.t) env.advance(b + 180 - env.t)
                g.down(env.t); env.advance(80); g.up(env.t)
            }
        }
        val p = ClickProfile.fromJson(env.saved)
        assertNotNull("saved: ${env.saved} logs ${env.logs.takeLast(5)}", p)
        assertEquals(180.0, p!!.meanMs, 10.0)
        assertTrue(p.count >= 8)
        assertTrue("click sound for every hit", env.hits >= p.count)
        assertTrue(env.spoken.any { it.startsWith("You click 18") || it.startsWith("You click 17") || it.startsWith("You click 19") })
        assertTrue(env.left)
    }

    @Test fun silentPlayerGetsNoProfile() {
        val env = Env()
        val g = ClickTestGame(env, env, { env.t }, { 0L }, seed = 3)
        g.start(); g.onSpeechDone()
        env.advance(120_000)
        assertNull(env.saved)
        assertTrue(env.spoken.contains(ClickTestVoice.NO_CLICKS.text))
        assertTrue(env.misses > 0)
    }

    @Test fun holdingLeaves() {
        val env = Env()
        val g = ClickTestGame(env, env, { env.t }, { 0L }, seed = 3)
        g.start(); g.onSpeechDone()
        g.down(env.t); env.advance(3100)
        assertTrue(env.left)
    }
}
