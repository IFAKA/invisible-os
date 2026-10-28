package com.faka.invisible.write

/** The chance of each next letter while a word is spelled, shared by the pair keyboard ([TypeProgram]) and the letter loop ([LoopItems]). */
object LetterRank {
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyzñ"
    const val ALPHABET_BY_USE = "eaosrnidltcumpbgvyqhfzjñxkw"   // common letters in Spanish and English
    /** Share of the letter chance that follows the letter pairs (e; swept 0.05 to 0.3 on tools/write_bench.py, see DESIGN-TYPING.md step 7). */
    const val SMOOTH = 0.2
    val LETTER_RE = Regex("[a-záéíóúüñ']+")

    /**
     * Every letter of the alphabet with its chance of being the next one after [prefix], most likely first; the chances sum to 1.
     * [all] = every word, most likely first (log chance). The chance among the letters of this prefix comes from the words that fit; the letter pairs ([grams], built once on demand) take [SMOOTH] of it, and all of it for a prefix no word starts with (a name); no word list at all gives A to Z.
     */
    fun letters(all: List<Pair<String, Double>>, prefix: String, grams: () -> LetterBigrams): List<Pair<Char, Double>> {
        val mass = LinkedHashMap<Char, Double>()
        for ((w, lp) in all) if (w.length > prefix.length && w.startsWith(prefix) && LETTER_RE.matches(w)) mass.merge(w[prefix.length], Math.exp(lp), Double::plus)
        val alpha = ALPHABET.toList()
        if (mass.isEmpty() && all.isEmpty()) return alpha.sorted().map { it to 1.0 / alpha.size }   // A to Z, so where a letter is can be predicted
        // common letters first: only breaks ties and orders letters with no weight
        val tie = { c: Char -> 1e-12 * (ALPHABET.length - ALPHABET_BY_USE.indexOf(c).let { i -> if (i < 0) ALPHABET.length else i }) }
        // smoothing: letter chance = (1 - e) * word-model chance + e * letter-bigram chance, so a letter no known word allows is never ~0 (a name: "bill" after "bil")
        val g = grams(); val prev = prefix.lastOrNull() ?: LetterBigrams.START
        val bTotal = alpha.sumOf { g.weight(prev, it) }
        val bChance = { c: Char -> if (bTotal > 0) g.weight(prev, c) / bTotal else 0.0 }
        val chance: (Char) -> Double = if (mass.isEmpty()) bChance else {   // masses are tiny (like a real model), so they are made to sum to 1
            val total = mass.values.sum(); val e = if (bTotal > 0) SMOOTH else 0.0
            { (1 - e) * (mass[it] ?: 0.0) / total + e * bChance(it) }
        }
        return alpha.sortedByDescending { chance(it) + tie(it) }.map { it to chance(it) }
    }
}
