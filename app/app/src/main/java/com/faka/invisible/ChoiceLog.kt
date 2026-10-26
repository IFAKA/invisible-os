package com.faka.invisible

/**
 * Local choice log (no network). One tab separated line per wake or code:
 * time, screen, code (0 = wake), intent, sender, tags, time-to-pick ms (list picks only). Counters give favourites (most frequent senders we replied to)
 * and intent frequency (for ordering later). `append` writes the line to disk.
 */
class ChoiceLog(initial: List<String> = emptyList(), private val append: (String) -> Unit = {}) {
    private val senders = HashMap<String, Int>()
    private val intents = HashMap<String, Int>()
    private val picks = ArrayList<Suggester.Pick>()

    init { initial.forEach { count(it) } }

    /** [tags] = the situation (see [Suggester.TAGS]) as a 6th column. */
    fun record(ts: Long, screen: String, code: Int, intent: String, sender: String = "", tags: Set<String> = emptySet(), pickMs: Long = -1): String {
        val cols = listOf(ts.toString(), screen, code.toString(), intent, sender, tags.sorted().joinToString(",")) + (if (pickMs >= 0) listOf(pickMs.toString()) else emptyList())   // 7th column (only for list picks): ms from list start to the press
        val line = cols.joinToString("\t") { it.replace(Regex("[\\t\\n\\r]"), " ") }
        count(line)
        append(line)
        return line
    }

    private fun count(line: String) {
        val p = line.split('\t')
        if (p.size < 5) return
        intents[p[3]] = (intents[p[3]] ?: 0) + 1
        p[0].toLongOrNull()?.let { picks += Suggester.Pick(it, p[3], p.getOrNull(5)?.split(',')?.filter { t -> t.isNotBlank() }?.toSet() ?: emptySet()) }
        if (p[3].startsWith("send") && p[4].isNotBlank()) senders[p[4]] = (senders[p[4]] ?: 0) + 1
    }

    fun intentCount(intent: String) = intents[intent] ?: 0

    /** Every logged pick in time order (for [Suggester]). */
    fun history(): List<Suggester.Pick> = picks

    /** Most frequent senders we sent replies to, best first. Empty at first. */
    fun favourites(n: Int = 3): List<String> =
        senders.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(n).map { it.key }
}
