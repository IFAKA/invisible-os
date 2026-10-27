package com.faka.invisible

import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.testing.SimRunner
import com.faka.invisible.voicekit.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintStream

class SimRunnerTest {
    /** Says "hi" at start; a press that ends in under 300 ms says "click" after 50 ms; held 300 ms or more ends. */
    private class P : Program {
        lateinit var h: ProgramHost
        var running = false; var downAt = 0L
        override val active get() = running
        override fun start(host: ProgramHost) { h = host; running = true; host.speak(Phrase.content("hi")) }
        override fun stop() { running = false; h.ended() }
        override fun down(time: Long) { downAt = time }
        override fun up(time: Long) {
            if (time - downAt >= 300) stop() else h.later(Runnable { h.speak(Phrase.content("click")) }, 50)
        }
    }

    @Test fun protocolLines() {
        val bytes = ByteArrayOutputStream()
        val w = PipedWriter(); val r = BufferedReader(PipedReader(w))
        val t = Thread { SimRunner(P(), PrintStream(bytes, true)).run(r) }
        t.start()
        w.write("down\nup\n"); w.flush(); Thread.sleep(200)
        w.write("down\n"); w.flush(); Thread.sleep(400); w.write("up\n"); w.flush()
        t.join(3000)
        val lines = bytes.toString().trim().lines()
        assertEquals(listOf("say hi", "ready", "say click", "ended"), lines)
        assertTrue(!t.isAlive)
    }

    @Test fun quitStopsTheProgram() {
        val bytes = ByteArrayOutputStream()
        val t = Thread { SimRunner(P(), PrintStream(bytes, true)).run(BufferedReader(java.io.StringReader("quit\n"))) }
        t.start(); t.join(3000)
        assertEquals(listOf("say hi", "ready", "ended"), bytes.toString().trim().lines())
    }
}
