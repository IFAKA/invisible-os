package com.faka.invisible.logickit

/**
 * Cuts a long text into the pieces the Reader tool speaks one by one. Pure.
 * A piece is a paragraph (blank line between), or a line if the text has no blank lines; links are removed; a piece
 * with no words is dropped; a piece longer than [MAX_WORDS] is cut at sentence ends so one piece is never a wall of speech.
 */
object Paragraphs {
    const val MAX_WORDS = 60
    /** A text longer than this is cut (the app keeps it in its saved settings). */
    const val MAX_CHARS = 100_000

    fun split(text: String): List<String> {
        val t = text.take(MAX_CHARS).replace("\r\n", "\n").replace('\r', '\n')
        val blocks = if (Regex("\\n[ \\t]*\\n").containsMatchIn(t)) t.split(Regex("\\n[ \\t]*\\n+")) else t.split('\n')
        return blocks.map { MessageLogic.stripLinks(it) }.filter { it.any(Char::isLetterOrDigit) }.flatMap { cut(it) }
    }

    /** Cuts [p] at sentence ends into pieces of at most [MAX_WORDS] words (a single longer sentence stays whole). */
    private fun cut(p: String): List<String> {
        if (p.words().size <= MAX_WORDS) return listOf(p)
        val out = ArrayList<String>(); var cur = StringBuilder(); var n = 0
        for (s in p.split(Regex("(?<=[.!?…])\\s+"))) {
            val w = s.words().size
            if (n > 0 && n + w > MAX_WORDS) { out.add(cur.toString().squash()); cur = StringBuilder(); n = 0 }
            cur.append(' ').append(s); n += w
        }
        if (n > 0) out.add(cur.toString().squash())
        return out
    }

    /** A short stable id of a text, to remember where the person stopped. */
    fun key(text: String): String {
        var h = 1125899906842597L
        for (c in text) h = 31 * h + c.code
        return java.lang.Long.toHexString(h) + "_" + text.length
    }
}
