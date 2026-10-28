package com.faka.invisible.logickit

/** What a typed text may mean (SpeakFaster style). Pure. The same rule as `completion_fits` in the bridge (`aitext.py`). */
object Completion {
    /**
     * Does [text] fit what was [typed]? Either the typed words are the start of the text's words (the last one may be cut: "meet me t" -> "meet me tomorrow"),
     * or the typed letters are the first letters of the text's words ("hay" -> "how are you", needs 2 letters or more).
     */
    fun fits(text: String, typed: String): Boolean {
        val tw = typed.words().map { it.lowercase() }
        val ow = text.words().map { it.lowercase() }
        if (tw.isEmpty() || ow.isEmpty()) return false
        if (tw.size <= ow.size && tw.dropLast(1).withIndex().all { (i, w) -> ow[i] == w } && ow[tw.size - 1].startsWith(tw.last())) return true
        val letters = tw.joinToString("").filter { it.isLetter() }
        val initials = ow.mapNotNull { w -> w.firstOrNull()?.takeIf { it.isLetter() } }.joinToString("")
        return letters.length >= 2 && initials.startsWith(letters)
    }
}
