package com.faka.invisible.write

/** The chance of each next letter while a word is spelled, shared by the pair keyboard ([TypeProgram]) and the letter loop ([LoopItems]). */
object LetterRank {
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyzñ"
    const val ALPHABET_BY_USE = "eaosrnidltcumpbgvyqhfzjñxkw"   // common letters in Spanish and English
    val LETTER_RE = Regex("[a-záéíóúüñ']+")

    /**
     * Every letter of the alphabet with its chance of being the next one after [prefix], most likely first; the chances sum to 1.
     * [all] = every word, most likely first (log chance). The chance among the letters of this prefix comes from the words that fit; a prefix no word
     * starts with (a name) follows the letter pairs ([grams], built once on demand); no word list at all gives A to Z.
     */
    fun letters(all: List<Pair<String, Double>>, prefix: String, grams: () -> LetterBigrams): List<Pair<Char, Double>> {
        val mass = LinkedHashMap<Char, Double>()
        for ((w, lp) in all) if (w.length > prefix.length && w.startsWith(prefix) && LETTER_RE.matches(w)) mass.merge(w[prefix.length], Math.exp(lp), Double::plus)
        val alpha = ALPHABET.toList()
        if (mass.isEmpty() && all.isEmpty()) return alpha.sorted().map { it to 1.0 / alpha.size }   // A to Z, so where a letter is can be predicted
        // common letters first: only breaks ties and orders letters with no weight
        val tie = { c: Char -> 1e-12 * (ALPHABET.length - ALPHABET_BY_USE.indexOf(c).let { i -> if (i < 0) ALPHABET.length else i }) }
        val chance: (Char) -> Double
        if (mass.isEmpty()) {   // no word starts so (a name): which letter follows the last one, from the word list
            val g = grams(); val prev = prefix.last()
            val total = alpha.sumOf { g.weight(prev, it) }.let { if (it > 0) it else 1.0 }
            chance = { g.weight(prev, it) / total }
        } else {   // masses are tiny (like a real model), so they are made to sum to 1
            val total = mass.values.sum()
            chance = { (mass[it] ?: 0.0) / total }
        }
        return alpha.sortedByDescending { chance(it) + tie(it) }.map { it to chance(it) }
    }
}
