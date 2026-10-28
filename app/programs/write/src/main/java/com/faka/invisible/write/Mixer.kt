package com.faka.invisible.write

import com.faka.invisible.logickit.Completion
import com.faka.invisible.logickit.words

/**
 * The one ranker of Write (Smart Compose style, arXiv 1906.00080): the chance of an item is the sum over the sources of
 * share(source) * P_source(item). Each source is first made a chance table over what fits the request (the word for the prefix, or a whole text);
 * the shares start equal and follow what the picks showed: a source that had the picked item in its top 3 earns trust, as the layers of
 * [BucketRanker] do (the trust is [BucketStore.trust]; the word model is the layer "base", which [BucketRanker.picked] already scores).
 * Answers come in at any time ([set]); they are used by the next [mix], which the loop calls once per cycle, never in the middle of one.
 */
class Mixer(private val trust: (String) -> Double = { 0.5 }, private val scored: (String, Boolean) -> Unit = { _, _ -> }) {
    /** [words]: (word, log chance) in the shape of [Ranker.ranked]; [phrases]: (text, chance in the mix, 0..1), best first. */
    class Mixed(val words: List<Pair<String, Double>>, val phrases: List<Pair<String, Double>>)

    private class Answer(val key: String, val guesses: List<Guess>)
    private val answers = LinkedHashMap<String, Answer>()
    private var lastWords: Map<String, List<String>> = emptyMap()     // source -> its words, best first (for learning)
    private var lastPhrases: Map<String, List<String>> = emptyMap()

    /** The answer of [source] to the request [key] (a newer answer of the same source replaces the older one). */
    fun set(source: String, key: String, guesses: List<Guess>) { answers[source] = Answer(key, guesses) }

    fun forget() { answers.clear() }

    private fun layer(id: String) = if (id == "lm") "base" else "src:$id"
    private fun share(ids: Collection<String>): Map<String, Double> { val w = ids.associateWith { trust(layer(it)) }; val z = w.values.sum().takeIf { it > 0 } ?: 1.0; return w.mapValues { it.value / z } }

    private fun normalized(m: Map<String, Double>): Map<String, Double> { val z = m.values.sum(); return if (z > 0) m.mapValues { it.value / z } else emptyMap() }

    /** A guess is a word (for the prefix) or a whole text. A one-word text and the one new word after the words written are words. */
    private fun split(req: Request, guesses: List<Guess>): Pair<Map<String, Double>, Map<String, Double>> {
        val wl = req.written.map { it.lowercase() }; val prefix = req.prefix.lowercase()
        val ws = LinkedHashMap<String, Double>(); val ps = LinkedHashMap<String, Double>()
        for (g in guesses) {
            val tw = g.text.words(); if (tw.isEmpty() || g.score <= 0) continue
            val low = tw.map { it.lowercase() }
            if (low.size == 1) { if (low[0].startsWith(prefix) && LetterRank.LETTER_RE.matches(low[0])) ws.merge(low[0], g.score, Double::plus); continue }
            if (req.typed.isNotEmpty() && !Completion.fits(g.text, req.typed)) continue
            if (low.size == wl.size + 1 && low.take(wl.size) == wl && low.last().startsWith(prefix) && LetterRank.LETTER_RE.matches(low.last())) ws.merge(low.last(), g.score, Double::plus)
            else ps.merge(tw.joinToString(" "), g.score, Double::plus)
        }
        return normalized(ws) to normalized(ps)
    }

    /** The mix for [req]; [lm] = the word model's words, most likely first (log chance). With no other answer the words are [lm] itself. */
    fun mix(req: Request, lm: List<Pair<String, Double>>): Mixed {
        val prefix = req.prefix.lowercase()
        val wordSrc = LinkedHashMap<String, Map<String, Double>>(); val phraseSrc = LinkedHashMap<String, Map<String, Double>>()
        for ((id, a) in answers) {
            if (a.key != req.key) continue
            val (w, p) = split(req, a.guesses)
            if (w.isNotEmpty()) wordSrc[id] = w
            if (p.isNotEmpty()) phraseSrc[id] = p
        }
        val ranksW = LinkedHashMap<String, List<String>>(); val ranksP = LinkedHashMap<String, List<String>>()
        var words = lm
        if (wordSrc.isNotEmpty()) {
            val fit = lm.filter { it.first.startsWith(prefix) && LetterRank.LETTER_RE.matches(it.first) }
            val mass = fit.sumOf { Math.exp(it.second) }
            val lmDist = if (mass > 0) fit.associate { it.first to Math.exp(it.second) / mass } else emptyMap()
            val all = LinkedHashMap<String, Map<String, Double>>(); if (lmDist.isNotEmpty()) all["lm"] = lmDist; all.putAll(wordSrc)
            val sh = share(all.keys)
            val mixed = HashMap<String, Double>()
            for ((id, d) in all) { for ((w, p) in d) mixed.merge(w, sh.getValue(id) * p, Double::plus); ranksW[id] = d.entries.sortedByDescending { it.value }.map { it.key } }
            val seen = mixed.keys.toHashSet()
            words = mixed.entries.sortedByDescending { it.value }.map { it.key to Math.log(it.value) } + lm.filter { it.first !in seen && !(it.first.startsWith(prefix) && LetterRank.LETTER_RE.matches(it.first)) }
        } else lm.filter { it.first.startsWith(prefix) }.take(5).map { it.first }.let { ranksW["lm"] = it }
        var phrases = emptyList<Pair<String, Double>>()
        if (phraseSrc.isNotEmpty()) {
            val sh = share(phraseSrc.keys)
            val mixed = LinkedHashMap<String, Double>()
            for ((id, d) in phraseSrc) { for ((t, p) in d) mixed.merge(t.lowercase(), sh.getValue(id) * p, Double::plus); ranksP[id] = d.entries.sortedByDescending { it.value }.map { it.key } }
            val shown = HashMap<String, String>(); phraseSrc.values.forEach { d -> d.keys.forEach { shown.putIfAbsent(it.lowercase(), it) } }
            phrases = mixed.entries.sortedByDescending { it.value }.map { shown.getValue(it.key) to it.value }
        }
        lastWords = ranksW; lastPhrases = ranksP
        return Mixed(words, phrases)
    }

    /**
     * The word or text [picked] was chosen: every source that had a table in the last [mix] is credited when it had the item in its top 3
     * (the word model is credited by [BucketRanker.picked], not here). Returns the source that ranked it best and the rank (null = nobody had it), for the trace.
     */
    fun picked(picked: String, isWord: Boolean): Pair<String, Int>? {
        val tables = if (isWord) lastWords else lastPhrases
        val p = picked.lowercase()
        var best: Pair<String, Int>? = null
        for ((id, list) in tables) {
            val rank = list.indexOfFirst { it.lowercase() == p }
            if (id != "lm") scored(layer(id), rank in 0..2)
            if (rank >= 0 && (best == null || rank < best.second)) best = id to rank
        }
        return best
    }
}
