package com.faka.invisible.logickit

/** Pure rules of the Habits tool. A day is an epoch day (days since 1970-01-01, local time). A habit keeps the days it was done. */
object Habits {
    /** The habits of a new person; the list can be replaced by the prefs key of the tool. */
    val STARTER = listOf("Water", "Walk", "Read")
    /** Only this many of the latest days are kept. */
    const val KEEP = 400

    /** Days in a row ending today. A day not answered yet does not break the streak: it counts from yesterday. */
    fun streak(done: Set<Long>, today: Long): Int {
        var d = if (today in done) today else today - 1
        var n = 0
        while (d in done) { n++; d-- }
        return n
    }

    /** Marks [day] done (yes) or not done (no). A "no" removes it, so a changed mind works both ways. */
    fun mark(done: Set<Long>, day: Long, yes: Boolean): Set<Long> =
        (if (yes) done + day else done - day).sortedDescending().take(KEEP).toSet()

    fun encode(done: Set<Long>): String = done.sortedDescending().joinToString(",")
    fun decode(text: String?): Set<Long> = text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /** The habit names from one saved text (one per line); blank lines go, repeats go. Empty = the starter list. */
    fun names(text: String?): List<String> = text.orEmpty().lines().map { it.squash() }.filter { it.isNotEmpty() }.distinct().ifEmpty { STARTER }
}
