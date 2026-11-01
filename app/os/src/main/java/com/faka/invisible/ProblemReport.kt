package com.faka.invisible

import com.faka.invisible.oskit.TraceFormat

/**
 * The "Send a problem report" text (pure). It holds the app and Android version and the trace files of the last [DAYS] days.
 * Nothing is sent by the app: the user picks where it goes in the Android share sheet.
 */
object ProblemReport {
    const val DAYS = 2

    /** The trace file names (trace-YYYY-MM-DD.log) that are not older than [DAYS] days before [nowMs], oldest first. */
    fun recent(names: List<String>, nowMs: Long): List<String> {
        val oldest = TraceFormat.fileName(nowMs - (DAYS - 1) * 86_400_000L)
        return names.filter { it.matches(Regex("trace-\\d{4}-\\d{2}-\\d{2}\\.log")) && it >= oldest }.sorted()
    }

    /** Header (versions) and each recent file under its own title. [files] = name to text. */
    fun build(appVersion: String, androidVersion: String, device: String, files: Map<String, String>, nowMs: Long): String {
        val sb = StringBuilder()
        sb.append("Invisible OS problem report\n")
        sb.append("App: ").append(appVersion).append('\n')
        sb.append("Android: ").append(androidVersion).append('\n')
        sb.append("Device: ").append(device).append('\n')
        val use = recent(files.keys.toList(), nowMs)
        if (use.isEmpty()) sb.append("\n(no trace files)\n")
        for (n in use) sb.append("\n==== ").append(n).append(" ====\n").append(files[n]!!.trimEnd()).append('\n')
        return sb.toString()
    }
}
