package com.faka.invisible.oskit

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * The trace log: one line per thing a person did or the phone answered, kept on disk for 7 days so a bug can be read after the fact.
 * File: `filesDir/trace/trace-YYYY-MM-DD.log`, line `HH:mm:ss.SSS kind detail`. Pull it with `tools/trace.py`.
 * Kinds (short): gesture, key, screen, say, pick, notif, music, media, wake, exit, program, error, slow.
 * Safe to call from any thread, before [init] (lines wait in memory) and in unit tests (no Android needed).
 * Every new behaviour a person would notice logs through here.
 */
object Trace {
    /** Anything slower than this (ms) on the main thread is a `slow` line. */
    const val SLOW_MS = 150L
    private const val TAG = "Invisible"
    private const val KEEP_DAYS = 7
    private const val FLUSH_MS = 1000L
    private val queue = LinkedBlockingQueue<Pair<Long, String>>(4000)
    @Volatile private var dir: File? = null
    @Volatile private var started = false

    /** Call once from the Application-level entry (service, Actions.init). Starts the writer and the crash handler. */
    @Synchronized fun init(ctx: Context) {
        if (started) return
        started = true
        val d = File(ctx.applicationContext.filesDir, "trace").apply { mkdirs() }
        dir = d
        try { TraceFormat.expired(d.list().orEmpty().toList(), System.currentTimeMillis(), KEEP_DAYS).forEach { File(d, it).delete() } } catch (_: Throwable) {}
        Thread({ writer() }, "trace-writer").apply { isDaemon = true; start() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { queue.offer(System.currentTimeMillis() to TraceFormat.clip("error crash thread=${t.name} ${TraceFormat.stack(e)}")); drain() } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        ev("wake", "trace on ${android.os.Build.MODEL}")
    }

    fun ev(kind: String, detail: String) {
        val now = System.currentTimeMillis()
        try { Log.d(TAG, "TRACE $kind $detail") } catch (_: Throwable) {}   // not mocked in unit tests
        queue.offer(now to "$kind $detail")
    }

    fun err(where: String, t: Throwable) = ev("error", "$where ${TraceFormat.stack(t)}")

    /** A measured step: always cheap to call; written as `slow` only when [ms] reaches [limit]. */
    fun time(kind: String, ms: Long, limit: Long = SLOW_MS) { if (ms >= limit) ev("slow", "$kind ${ms}ms") }

    private fun writer() {
        while (true) {
            try {
                Thread.sleep(FLUSH_MS)
                drain()
            } catch (_: InterruptedException) { return } catch (_: Throwable) {}
        }
    }

    /** Write everything queued. Called by the writer every second and by the crash handler. */
    @Synchronized private fun drain() {
        val d = dir ?: return
        if (queue.isEmpty()) return
        val sb = HashMap<String, StringBuilder>()
        while (true) {
            val (t, text) = queue.poll() ?: break
            sb.getOrPut(TraceFormat.fileName(t)) { StringBuilder() }.append(TraceFormat.line(t, text)).append('\n')
        }
        for ((name, text) in sb) try { File(d, name).appendText(text.toString()) } catch (_: Throwable) {}
    }
}

/** The pure parts of the trace: line format, file names, which files are too old. */
object TraceFormat {
    private const val MAX = 600
    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US)

    fun fileName(ms: Long) = "trace-" + fmt("yyyy-MM-dd").format(Date(ms)) + ".log"
    fun line(ms: Long, text: String) = fmt("HH:mm:ss.SSS").format(Date(ms)) + " " + clip(text)

    /** One line only: line breaks become " | ", long text is cut. */
    fun clip(s: String): String {
        val one = s.trim().replace("\r", "").replace('\n', '|')
        return if (one.length <= MAX) one else one.take(MAX) + "..."
    }

    /** The exception and where it came from, on one line: "Type: message at A.b(F.kt:1) | at ...", the first 6 frames. */
    fun stack(t: Throwable): String {
        val frames = t.stackTrace.take(6).joinToString(" | ") { "at $it" }
        return "${t.javaClass.name}: ${t.message} $frames"
    }

    /** The files in [names] older than [keepDays] days before [nowMs]. Names that are not trace files are never chosen. */
    fun expired(names: List<String>, nowMs: Long, keepDays: Int): List<String> {
        val oldest = fileName(nowMs - keepDays * 86_400_000L)
        return names.filter { it.matches(Regex("trace-\\d{4}-\\d{2}-\\d{2}\\.log")) && it < oldest }
    }
}
