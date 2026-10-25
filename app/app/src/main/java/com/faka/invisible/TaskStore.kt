package com.faka.invisible

/** One open to-do item. */
data class Task(val id: Long, val text: String)

/**
 * Pure: the open to-do list, oldest first. Done items are removed. [lines] = saved form ("id<TAB>text" per line);
 * `save` gets the new lines after every change.
 */
class TaskStore(lines: List<String> = emptyList(), private val save: (List<String>) -> Unit = {}) {
    private val list = ArrayList<Task>()
    private var nextId = 1L

    init {
        for (l in lines) {
            val i = l.indexOf('\t')
            val id = if (i > 0) l.substring(0, i).toLongOrNull() else null
            if (id != null && i < l.length - 1) { list.add(Task(id, l.substring(i + 1))); nextId = maxOf(nextId, id + 1) }
        }
    }

    /** Adds a task (blank text is ignored, line breaks become spaces). Returns it, or null if blank. */
    @Synchronized fun add(text: String): Task? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        return Task(nextId++, t).also { list.add(it); persist() }
    }

    @Synchronized fun done(id: Long) { if (list.removeAll { it.id == id }) persist() }
    @Synchronized fun all(): List<Task> = list.toList()
    private fun persist() = save(list.map { "${it.id}\t${it.text}" })
}
