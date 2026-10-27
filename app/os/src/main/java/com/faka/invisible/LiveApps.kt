package com.faka.invisible

/** What an app shows that may be live: a media session, or an ongoing notification. Times are in the same clock as `now` (ms). */
data class LiveCandidate(
    val pkg: String, val app: String,
    val updatedAt: Long,
    /** A media session of the app: playing now, paused (then [updatedAt] says since when). */
    val mediaPlaying: Boolean = false, val mediaPaused: Boolean = false,
    /** An ongoing notification of the app (key set), with what makes it live. */
    val notifKey: String? = null, val hasActions: Boolean = false, val chronometer: Boolean = false,
    val category: String = "", val mediaStyle: Boolean = false,
    val line: String = "", val hidden: Boolean = false,
)

/** One live entry on home. [state] is "playing", "paused" or "live"; [line] is the notification's short line when it is worth saying. */
data class LiveApp(val pkg: String, val app: String, val state: String, val line: String, val notifKey: String?, val hidden: Boolean, val updatedAt: Long)

/**
 * Pure: which apps are "live" and go first on home. Live = a media session that plays or was paused in the last [PAUSED_MS], or an ongoing
 * notification that is not static: it has actions, a chronometer, a media style, or a category in [CATEGORIES].
 * Static ongoing ones (USB debugging, battery, "running in background", VPN) are not live. Most recently updated first (a workout
 * that ticks every second comes before music), one entry per app, at most [MAX].
 */
object LiveApps {
    const val MAX = 3
    const val PAUSED_MS = 10 * 60_000L
    const val LINE_MAX = 40
    val CATEGORIES = setOf("transport", "progress", "stopwatch", "navigation", "call", "workout")

    fun notifIsLive(hasActions: Boolean, chronometer: Boolean, category: String, mediaStyle: Boolean) =
        hasActions || chronometer || mediaStyle || category in CATEGORIES

    private fun isLive(c: LiveCandidate, now: Long): Boolean =
        c.mediaPlaying || (c.mediaPaused && now - c.updatedAt <= PAUSED_MS) ||
            (c.notifKey != null && notifIsLive(c.hasActions, c.chronometer, c.category, c.mediaStyle))

    fun pick(cands: List<LiveCandidate>, now: Long, self: String, muted: (String) -> Boolean, max: Int = MAX): List<LiveApp> =
        cands.filter { it.pkg != self && !muted(it.pkg) && isLive(it, now) }
            .groupBy { it.pkg }
            .map { (_, cs) ->
                val c = cs.maxByOrNull { it.updatedAt }!!
                val media = cs.firstOrNull { it.mediaPlaying || it.mediaPaused }
                val note = cs.firstOrNull { it.notifKey != null && notifIsLive(it.hasActions, it.chronometer, it.category, it.mediaStyle) }
                val state = when { media?.mediaPlaying == true -> "playing"; media != null && note == null -> "paused"; else -> "live" }
                val line = if (c.hidden || note?.hidden == true) "" else shortLine(note?.line.orEmpty(), c.app)
                LiveApp(c.pkg, c.app, state, if (state == "playing") "" else line, note?.notifKey, cs.any { it.hidden }, c.updatedAt)
            }
            .sortedByDescending { it.updatedAt }.take(max)

    /** The notification's own short line, or "" if it is empty, just the app name, or too long to be a status. */
    fun shortLine(line: String, app: String): String {
        val t = line.trim()
        return if (t.isEmpty() || t.equals(app, true) || t.length > LINE_MAX) "" else t
    }
}
