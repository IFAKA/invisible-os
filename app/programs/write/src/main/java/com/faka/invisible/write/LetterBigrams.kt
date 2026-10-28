package com.faka.invisible.write

/**
 * Which letter follows a letter, learned from a word list (most likely word first; weight 1 / (rank + 10)).
 * Mixed into the letter chances of every prefix ([LetterRank.SMOOTH]); alone when no word starts with the spelled letters (a name): the next letters are ranked by it, not A to Z.
 */
class LetterBigrams(words: List<String>) {
    private val next = HashMap<Char, HashMap<Char, Double>>()
    init {
        words.forEachIndexed { rank, w ->
            val weight = 1.0 / (rank + 10)
            if (w.isNotEmpty()) next.getOrPut(START) { HashMap() }.merge(w[0], weight, Double::plus)
            for (i in 0 until w.length - 1) next.getOrPut(w[i]) { HashMap() }.merge(w[i + 1], weight, Double::plus)
        }
    }
    /** How much [c] follows [prev]; 0 when never seen. */
    fun weight(prev: Char, c: Char): Double = next[prev]?.get(c) ?: 0.0
    val isEmpty get() = next.isEmpty()
    companion object { /** "Before the first letter" in [weight]. */ const val START = '^' }
}
