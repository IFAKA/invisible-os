package com.faka.invisible.claude

import com.faka.invisible.logickit.firstWords
import com.faka.invisible.logickit.squash

/** One thing the laptop sent: a question that needs an answer (permission, question) or a short note (done, needs). */
data class ClaudeEvent(val id: String, val kind: String, val project: String, val summary: String, val options: List<String>) {
    val needsAnswer get() = options.isNotEmpty() && (kind == KIND_PERMISSION || kind == KIND_QUESTION)

    companion object {
        const val KIND_PERMISSION = "permission"
        const val KIND_QUESTION = "question"
        const val KIND_DONE = "done"
        const val KIND_NEEDS = "needs"
        const val MAX_WORDS = 12
        const val MAX_OPTIONS = 6

        /** A clean event from raw fields: summary at most [MAX_WORDS] words (a note: 25), options without blanks or repeats. Null if it has no id. */
        fun of(id: String?, kind: String?, project: String?, summary: String?, options: List<String>): ClaudeEvent? {
            if (id.isNullOrBlank()) return null
            val k = when (kind) { KIND_PERMISSION, KIND_QUESTION, KIND_DONE, KIND_NEEDS -> kind; else -> KIND_NEEDS }
            val opts = options.map { it.squash() }.filter { it.isNotEmpty() }.distinct().take(MAX_OPTIONS)
            val words = if (opts.isEmpty()) 25 else MAX_WORDS
            return ClaudeEvent(id, k, project.orEmpty().squash().ifEmpty { "Claude" }, summary.orEmpty().firstWords(words), opts)
        }
    }
}

/** What a click in the answer list can pick: one of the laptop's options, or "Read again". */
sealed class Choice {
    data class Option(val text: String) : Choice()
    object Again : Choice()
    companion object { fun of(e: ClaudeEvent): List<Choice> = e.options.map { Option(it) } + Again }
}

/** Events waiting to be spoken, oldest first. An id is taken once; an answered or skipped id never comes back. */
class ClaudeQueue {
    private val waiting = ArrayDeque<ClaudeEvent>()
    private val seen = HashSet<String>()
    val size get() = waiting.size

    /** True if it was new. */
    fun add(e: ClaudeEvent): Boolean { if (!seen.add(e.id)) return false; waiting.addLast(e); return true }
    fun next(): ClaudeEvent? = waiting.removeFirstOrNull()
    /** Questions first: a note never makes a question wait. */
    fun nextByUrgency(): ClaudeEvent? {
        val e = waiting.firstOrNull { it.needsAnswer } ?: return next()
        waiting.remove(e); return e
    }
}
