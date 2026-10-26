package com.faka.invisible.echo

import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Timers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole game on a virtual clock: a perfect player climbs, a silent player loses three hearts and the game ends. */
class EchoGameTest {
    private class Env : EchoOut, Timers {
        var t = 0L                                  // ms
        val due = ArrayList<Triple<String, Long, () -> Unit>>()
        val robot = ArrayList<Long>()               // frames of robot hits, this round
        val spoken = ArrayList<String>(); val logs = ArrayList<String>()
        var saved = -1; var left = false
        override fun nowFrame() = t * EchoSynth.SR / 1000
        override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) { if (sound === EchoSynth.robot) robot.add(atFrame) }
        override fun speak(text: Phrase) { spoken.add(text.text) }
        override fun stopSpeech() {}
        override fun best() = 0
        override fun saveBest(score: Int) { saved = score }
        override fun log(line: String) { logs.add(line) }
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

    private fun session(player: (Env, EchoGame) -> Unit): Env {
        val env = Env()
        val g = EchoGame(env, env, { env.t }, { 0L }, seed = 5)
        g.start(); g.onSpeechDone()
        player(env, g)
        return env
    }

    @Test fun perfectPlayerClimbs() {
        val env = session { e, g ->
            var rounds = 0
            while (!e.left && rounds < 12) {
                val hits = e.robot.toList(); e.robot.clear()
                val lvl = EchoLadder.level(rounds + 1)
                val barFrames = (lvl.slots * lvl.slotMs * EchoSynth.SR / 1000).toLong()
                for (f in hits.sorted()) {
                    val at = (f + barFrames) * 1000 / EchoSynth.SR
                    e.advance(at - e.t); g.down(e.t); g.up(e.t + 20)
                }
                e.advance(8000)
                rounds++
            }
        }
        val cleared = env.logs.count { it.startsWith("echo bar") && "cleared=true" in it }
        assertTrue("perfect player cleared $cleared bars\n${env.logs.joinToString("\n")}", cleared >= 10)
        assertTrue(env.logs.any { "perfect=true" in it })
    }

    @Test fun silentPlayerLosesAndTheGameEnds() {
        val env = session { e, _ -> repeat(10) { e.advance(20000) } }
        assertEquals(3, env.logs.count { it.startsWith("echo bar") && "cleared=false" in it })
        assertTrue(env.spoken.any { it.startsWith("Game over") })
        assertEquals(listOf("Two lives.", "Last life."), env.spoken.filter { it.endsWith("lives.") || it == "Last life." })
        assertTrue(env.left)
    }

    @Test fun holdingThreeSecondsLeaves() {
        val env = session { e, g -> e.advance(500); g.down(e.t); e.advance(3100) }
        assertTrue(env.left)
    }
}
