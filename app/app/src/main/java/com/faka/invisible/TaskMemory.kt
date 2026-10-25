package com.faka.invisible

/**
 * Pure: remembers every task text the user added, so "Add task" can offer them again (their own words, nothing to set up).
 * Offers: most added first, then most recent; message-made tasks ("Reply to ...") are not offered; open tasks are left out.
 * [lines] = saved form ("count<TAB>lastMs<TAB>lang<TAB>text"); `save` gets all lines after each change.
 */
class TaskMemory(lines: List<String> = emptyList(), private val clock: () -> Long = { System.currentTimeMillis() }, private val save: (List<String>) -> Unit = {}) {
    private class E(var count: Int, var last: Long, var lang: String, val text: String)
    private val items = LinkedHashMap<String, E>()   // key = lower-case text

    init {
        for (l in lines) {
            val p = l.split('\t', limit = 4)
            if (p.size == 4) p[0].toIntOrNull()?.let { c -> p[1].toLongOrNull()?.let { t -> items[p[3].lowercase()] = E(c, t, p[2], p[3]) } }
        }
    }

    @Synchronized fun record(text: String, lang: String) {
        val e = items.getOrPut(text.lowercase()) { E(0, 0, lang, text) }
        e.count++; e.last = clock(); if (lang.isNotEmpty()) e.lang = lang
        if (items.size > MAX) items.values.minWithOrNull(compareBy({ it.count }, { it.last }))?.let { items.remove(it.text.lowercase()) }
        save(items.values.map { "${it.count}\t${it.last}\t${it.lang}\t${it.text}" })
    }

    /** Up to [n] offers, skipping the texts in [open] (lower-case). */
    @Synchronized fun offers(n: Int, open: Set<String>): List<Task> =
        items.values.filter { it.text.lowercase() !in open && !it.text.startsWith("Reply to ") }
            .sortedWith(compareBy({ -it.count }, { -it.last })).take(n).map { Task(0, it.text, it.lang) }

    companion object { const val MAX = 100 }
}
