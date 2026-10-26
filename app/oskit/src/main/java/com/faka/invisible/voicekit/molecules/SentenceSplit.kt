package com.faka.invisible.voicekit

/**
 * MOLECULE. Cuts a text into sentences for the neural voice, so the first sound can start after the first sentence only.
 * Pure Kotlin. A "." between digits ("3.5") or without a space after it ("a.com") does not end a sentence.
 * Pieces shorter than [MIN] join the next one; pieces longer than [MAX] are cut at a comma or a space.
 */
object SentenceSplit {
    const val MIN = 12
    const val MAX = 220

    fun split(text: String): List<String> {
        val raw = ArrayList<String>(); val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]; cur.append(c)
            val end = when (c) {
                '\n' -> true
                '.', '!', '?', '…' -> {
                    var j = i + 1
                    while (j < text.length && text[j] in ".!?…\"')”»") { cur.append(text[j]); j++ }
                    i = j - 1
                    j >= text.length || text[j].isWhitespace()
                }
                else -> false
            }
            if (end) { raw += cur.toString(); cur.clear() }
            i++
        }
        if (cur.isNotBlank()) raw += cur.toString()
        val out = ArrayList<String>(); val join = StringBuilder()
        for (r in raw) {
            val t = r.trim(); if (t.none { it.isLetterOrDigit() }) continue
            join.append(if (join.isEmpty()) t else " $t")
            if (join.length >= MIN) { out += cutLong(join.toString()); join.clear() }
        }
        if (join.isNotEmpty()) { if (out.isNotEmpty() && join.length < MIN) out[out.size - 1] = out.last() + " " + join else out += join.toString() }
        return out
    }

    private fun cutLong(s: String): List<String> {
        if (s.length <= MAX) return listOf(s)
        val r = ArrayList<String>(); var rest = s
        while (rest.length > MAX) {
            var k = rest.lastIndexOf(',', MAX).takeIf { it > MIN } ?: rest.lastIndexOf(' ', MAX).takeIf { it > MIN } ?: MAX
            if (k <= 0) k = MAX
            r += rest.substring(0, k + if (rest[k] == ',') 1 else 0).trim(); rest = rest.substring(k + 1).trim()
        }
        if (rest.isNotEmpty()) r += rest
        return r
    }
}
