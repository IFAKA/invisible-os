package com.faka.invisible.oskit.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Phrase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs a program on the laptop with a REAL clock, no phone (`invisible sim NAME`). One thread plays the "main thread".
 *
 * Line protocol. In (stdin): `down`, `up`, `quit`. Out (stdout), one line each:
 *   `ready`          the program started
 *   `say TEXT`       the program spoke TEXT
 *   `stop`           the program cut its speech
 *   `sound NAME`     a host sound (`waiting`)
 *   `log TEXT`       a log line
 *   `ended`          the program called ended(); the runner exits
 * Needs `testOptions.unitTests.isReturnDefaultValues = true` (a stub Handler is created), like [FakeProgramHost].
 */
class SimRunner(private val program: Program, private val out: PrintStream) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-main").apply { isDaemon = true } }
    private val t0 = System.currentTimeMillis()
    private val pending = HashMap<Runnable, ScheduledFuture<*>>()
    private val done = CountDownLatch(1)

    private fun now() = System.currentTimeMillis() - t0
    private fun emit(kind: String, text: String = "") =
        synchronized(out) { out.println(if (text.isEmpty()) kind else kind + " " + text.replace('\n', ' ').replace('\r', ' ')); out.flush() }
    private fun <T> onMain(f: () -> T): T = exec.submit(Callable { f() }).get()

    private val host = object : ProgramHost {
        override val handler: Handler = Handler()
        override fun later(r: Runnable, delayMs: Long) {
            val f = exec.schedule({ synchronized(pending) { pending.remove(r) }; r.run() }, delayMs, TimeUnit.MILLISECONDS)
            synchronized(pending) { pending.put(r, f)?.cancel(false) }
        }
        override fun cancel(r: Runnable) { synchronized(pending) { pending.remove(r) }?.cancel(false) }
        override val context: Context = ContextWrapper(null)
        override val prefs: SharedPreferences = FakeProgramHost.MemoryPrefs()
        override fun waiting(): () -> Unit { emit("sound", "waiting"); return {} }
        override fun speak(text: Phrase, slow: Boolean) = emit("say", text.text)
        override fun stopSpeech() = emit("stop")
        override fun pauseMusic() {}
        override fun resumeMusic() {}
        override fun log(line: String) = emit("log", line)
        override fun outputDevice() = "sim"
        override fun ended() { emit("ended"); done.countDown() }
    }

    /** Starts the program, then reads commands until `quit`, end of input or the program ends. */
    fun run(input: BufferedReader) {
        onMain { program.start(host) }
        emit("ready")
        val reader = Thread {
            while (true) {
                val line = input.readLine()?.trim() ?: break
                when (line) {
                    "down" -> exec.execute { program.down(now()) }
                    "up" -> exec.execute { program.up(now()) }
                    "quit" -> break
                }
            }
            done.countDown()
        }.apply { isDaemon = true; start() }
        done.await()
        if (program.active) try { onMain { program.stop() } } catch (_: Exception) {}
        exec.shutdownNow()
    }
}

/** `java -cp ... SimMain NAME`: finds `com.faka.invisible.NAME.<Name>Entries.entry`, runs it on stdin/stdout. */
object SimMain {
    @JvmStatic fun main(args: Array<String>) {
        val name = args.firstOrNull() ?: run { System.err.println("usage: SimMain NAME"); System.exit(2); return }
        val cap = name.replaceFirstChar { it.uppercase() }
        val cls = try { Class.forName("com.faka.invisible.$name.${cap}Entries") } catch (e: ClassNotFoundException) {
            System.err.println("No ${cap}Entries in package com.faka.invisible.$name"); System.exit(2); return
        }
        val entry = cls.getMethod("getEntry").invoke(cls.getField("INSTANCE").get(null)) as ProgramEntry
        val out = System.out
        System.setOut(System.err)   // stray prints from the program must not break the protocol
        SimRunner(entry.create(), out).run(BufferedReader(InputStreamReader(System.`in`)))
        out.flush()
        System.exit(0)
    }
}
