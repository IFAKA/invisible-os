package com.faka.invisible.logickit

/** One unread conversation: key identifies it for replies. lang = detected language ("es", "en", ...) or "". at = arrival time (ms). */
data class Msg(val key: String, val sender: String, val text: String, val lang: String = "", val at: Long = 0)

/** Pure: latest unread message per conversation key, newest first, capped. */
class MessageStore(private val max: Int = 20) {
    private val list = ArrayList<Msg>()

    @Synchronized fun upsert(m: Msg) {
        list.removeAll { it.key == m.key }
        list.add(0, m)
        while (list.size > max) list.removeAt(list.size - 1)
    }

    /** Sets the detected language if that conversation still holds the same text. */
    @Synchronized fun setLang(key: String, text: String, lang: String) {
        val i = list.indexOfFirst { it.key == key && it.text == text }
        if (i >= 0) list[i] = list[i].copy(lang = lang)
    }

    @Synchronized fun remove(key: String) { list.removeAll { it.key == key } }
    @Synchronized fun removeWhere(pred: (Msg) -> Boolean) { list.removeAll(pred) }
    @Synchronized fun clear() = list.clear()
    @Synchronized fun all(): List<Msg> = list.toList()
}

/**
 * Pure: one language per person, by vote. Each clear message adds a vote; the language with most votes wins.
 * Once one language has [SETTLED] votes it is final (detection stops). `save` persists "lang:votes,lang:votes" per person.
 */
class ConvLang(initial: Map<String, String> = emptyMap(), private val save: (Map<String, String>) -> Unit = {}) {
    companion object { const val SETTLED = 3; const val LEAD = 2; const val DEFAULT = "es" }
    private val votes = HashMap<String, HashMap<String, Int>>()

    init {
        for ((who, v) in initial) votes[who] = HashMap<String, Int>().apply {
            v.split(',').forEach { p -> val a = p.split(':'); if (a.size == 2) a[1].toIntOrNull()?.let { put(a[0], it) } }
        }
    }

    /** Spanish unless another language has clearly more votes (at least [LEAD] more than Spanish). */
    @Synchronized fun get(who: String): String {
        val m = votes[who] ?: return DEFAULT
        val top = m.filterKeys { it != DEFAULT }.maxByOrNull { it.value } ?: return DEFAULT
        return if (top.value >= (m[DEFAULT] ?: 0) + LEAD) top.key else DEFAULT
    }

    @Synchronized fun settled(who: String): Boolean = (votes[who]?.values?.maxOrNull() ?: 0) >= SETTLED

    /** Adds a vote for `lang` (ignored if empty or already settled). Returns the language now in force. */
    @Synchronized fun learn(who: String, lang: String): String {
        if (lang.isEmpty() || settled(who)) return get(who)
        val m = votes.getOrPut(who) { HashMap() }
        m[lang] = (m[lang] ?: 0) + 1
        save(votes.mapValues { e -> e.value.entries.joinToString(",") { "${it.key}:${it.value}" } })
        return get(who)
    }
}
