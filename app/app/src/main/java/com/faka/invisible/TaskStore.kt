package com.faka.invisible

import com.faka.invisible.logickit.*

/** One open to-do item. */
/** lang = voice language detected when it was added ("es", "en"), "" if unknown. */
data class Task(val id: Long, val text: String, val lang: String = "")

/**
 * Pure: the open to-do list, oldest first. Done items are removed. [lines] = saved form ("id<TAB>lang<TAB>text" per line; older saves have no lang);
 * `save` gets the new lines after every change.
 */
class TaskStore(lines: List<String> = emptyList(), private val onAdd: (Task) -> Unit = {}, private val save: (List<String>) -> Unit = {}) {
    private val list = ArrayList<Task>()
    private var nextId = 1L

    init {
        for (l in lines) {
            val p = l.split('\t', limit = 3)
            val id = p.getOrNull(0)?.toLongOrNull() ?: continue
            val t = if (p.size == 3) Task(id, p[2], p[1]) else if (p.size == 2) Task(id, p[1]) else continue
            if (t.text.isNotEmpty()) { list.add(t); nextId = maxOf(nextId, id + 1) }
        }
    }

    /** Adds a task (line breaks become spaces). Returns it, or null if blank or the same task is already open. */
    @Synchronized fun add(text: String, lang: String = ""): Task? {
        val t = text.squash()
        if (t.isEmpty() || list.any { it.text.equals(t, ignoreCase = true) }) return null   // blank or already there
        return Task(nextId++, t, lang).also { list.add(it); persist(); onAdd(it) }
    }

    /** Adds every task found in [text] (see [parse]). Returns how many were added. */
    fun addAll(text: String, detect: (String) -> String = { "" }): Int = parse(text).count { add(it, detect(it)) != null }

    @Synchronized fun done(id: Long) { if (list.removeAll { it.id == id }) persist() }
    /** Moves the task at position [from] to position [to] (both 0-based). */
    @Synchronized fun move(from: Int, to: Int) {
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from)); persist()
    }

    @Synchronized fun all(): List<Task> = list.toList()
    companion object {
        private val box = Regex("""\[[ xX✓✔]?\]|[☐☑☒✅⬜]""")
        private val bullet = Regex("""^\s*(?:[-*•▪◦]|\d+[.)])\s+(.+)$""")

        /**
         * Splits a shared note into tasks. Checklist (Keep: "[ ] open", "[X] done"): only the open items, even if the lines
         * were joined. Else bullet or numbered lines. Else the whole text is one task.
         */
        fun parse(text: String): List<String> {
            val t = text.replace("\r", "")
            if (box.containsMatchIn(t)) {
                val starts = box.findAll(t).toList()
                return starts.mapIndexedNotNull { i, m ->
                    val end = starts.getOrNull(i + 1)?.range?.first ?: t.length
                    val checked = if (m.value.length > 1) m.value[1] != ' ' else m.value in "☑☒✅"
                    t.substring(m.range.last + 1, end).squash().takeIf { !checked && it.isNotEmpty() }
                }
            }
            val bullets = t.lines().mapNotNull { bullet.find(it)?.groupValues?.get(1)?.trim() }
            if (bullets.isNotEmpty()) return bullets
            return listOf(t.squash()).filter { it.isNotEmpty() }
        }
    }

    private fun persist() = save(list.map { "${it.id}\t${it.lang}\t${it.text}" })
}
