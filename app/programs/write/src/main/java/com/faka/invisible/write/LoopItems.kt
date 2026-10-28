package com.faka.invisible.write

import com.faka.invisible.voicekit.LoopItem
import com.faka.invisible.voicekit.Phrase

/** What the letter loop offers in one round, and the chance of each item. Pure. */
object LoopItems {
    const val WORDS = 3
    const val SPACE = "space"; const val UNDO = "undo"; const val SEND = "send"
    fun word(w: String) = "w:$w"
    fun letter(c: Char) = "l:$c"
    fun phrase(i: Int) = "p:$i"

    /** Share of the chance kept for the two whole-text phrases, each. */
    private const val PHRASE_P = 0.15
    private const val PHRASE_MIN = 0.05
    private const val PHRASE_MAX = 0.3

    /** The chance of a whole text from its chance in the mix of the sources ([Mixer]): the best ones get the most, none more than [PHRASE_MAX]. */
    fun phraseChance(mixed: Double) = PHRASE_MIN + (PHRASE_MAX - PHRASE_MIN) * mixed.coerceIn(0.0, 1.0)
    private const val CONTROL_P = 0.02
    private const val SEND_AFTER_SENTENCE_P = 0.2

    /**
     * Items in the order of the chance of the guesses first (up to [phrases] 2-3 whole texts, up to [WORDS] words for the prefix), then every letter, then Space (only
     * while a word is being spelled), Undo (only when there is something to undo) and Send, last, so they sit at the end of each channel.
     * Chances: a word = its chance among the words that fit the prefix; a letter = its letter chance times what the guesses leave; the controls are small and fixed (Send is bigger when the text ends a sentence).
     */
    fun build(
        all: List<Pair<String, Double>>, written: List<String>, prefix: String, skipShortFirst: Boolean,
        phrases: List<String>, canUndo: Boolean, endsSentence: Boolean, grams: () -> LetterBigrams, phraseChances: List<Double> = emptyList(),
    ): List<LoopItem<String>> {
        val recent = written.takeLast(3).toSet()   // a word just written is not offered again at once
        val fits = all.filter { it.first.startsWith(prefix) && LetterRank.LETTER_RE.matches(it.first) }
        val mass = fits.sumOf { Math.exp(it.second) }
        val guesses = fits.asSequence().filter { it.first !in recent }
            .filter { prefix.isNotEmpty() || !skipShortFirst || (it.first.length >= 3 && '\'' !in it.first) }.take(WORDS).toList()
        val pWords = guesses.map { if (mass > 0) Math.exp(it.second) / mass else 0.0 }
        val letters = LetterRank.letters(all, prefix, grams)
        val left = (1.0 - pWords.sum()).coerceAtLeast(0.2)
        val phraseOf = { i: Int -> phraseChances.getOrNull(i) ?: PHRASE_P }
        val phraseP = phrases.indices.sumOf { phraseOf(it) }
        val hasText = written.isNotEmpty() || prefix.isNotEmpty()
        val controls = (if (prefix.isNotEmpty()) CONTROL_P else 0.0) + (if (canUndo) CONTROL_P else 0.0) + sendP(hasText, endsSentence)
        val k = (1.0 - controls - phraseP).coerceAtLeast(0.3)

        val out = ArrayList<LoopItem<String>>()
        phrases.forEachIndexed { i, t -> out.add(LoopItem(phrase(i), Phrase.content(t), phraseOf(i))) }
        guesses.forEachIndexed { i, (w, _) -> out.add(LoopItem(word(w), Phrase.content(w), pWords[i] * k)) }
        for ((c, chance) in letters) out.add(LoopItem(letter(c), Phrase.content(c.toString()), chance * left * k))   // no fixed floor: the letter pairs give every letter a share
        if (prefix.isNotEmpty()) out.add(LoopItem(SPACE, TypeVoice.SPACE, CONTROL_P, risky = false))
        if (canUndo) out.add(LoopItem(UNDO, TypeVoice.LOOP_UNDO, CONTROL_P, undo = true, risky = true))
        out.add(LoopItem(SEND, TypeVoice.LOOP_SEND, sendP(hasText, endsSentence), risky = true))
        return out
    }

    private fun sendP(hasText: Boolean, endsSentence: Boolean) = if (!hasText) 0.005 else if (endsSentence) SEND_AFTER_SENTENCE_P else CONTROL_P
}
