package com.faka.invisible.logickit

/** What a typed text may mean (SpeakFaster style). Pure. The same rule as `completion_fits` in the bridge (`aitext.py`). */
object Completion {
    /**
     * Does [text] fit what was typed: the finished words [done] and the letters [letters] of the word being spelled?
     * It must start with [done]. Then EITHER its next word starts with [letters] and is longer ("t" -> "tomorrow"; equal only when [wholeWord],
     * the caller knows the letters are a whole word), OR the first letters of its next words start with [letters] ("hay" -> "how are you", 2 letters or more).
     * The letters copied as a word ("hay how are you") do not fit.
     */
    fun fits(text: String, done: List<String>, letters: String, wholeWord: Boolean = false): Boolean {
        val d = done.flatMap { it.words() }.map { it.lowercase() }
        val l = letters.words().joinToString("").lowercase()
        val ow = text.words().map { it.lowercase() }
        if (ow.size < d.size || d.indices.any { ow[it] != d[it] }) return false
        val rest = ow.drop(d.size)
        if (l.isEmpty()) return rest.isNotEmpty()
        if (rest.isEmpty()) return false
        val w = rest[0]
        if (w.startsWith(l) && (w.length > l.length || wholeWord)) return true
        val initials = rest.mapNotNull { x -> x.firstOrNull()?.takeIf { it.isLetter() } }.joinToString("")
        val ls = l.filter { it.isLetter() }
        return ls.length >= 2 && initials.startsWith(ls)
    }

    /** Old callers: [typed] is the finished words and then the letters ("meet me t"); the last word is read as the letters. */
    fun fits(text: String, typed: String): Boolean {
        val tw = typed.words()
        if (tw.isEmpty()) return false
        return fits(text, tw.dropLast(1), tw.last())
    }
}
