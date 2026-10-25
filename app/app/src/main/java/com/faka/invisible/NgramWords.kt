package com.faka.invisible

import kotlin.math.ln

/**
 * Your own next-word model, built on this laptop from your WhatsApp chats by tools/build_es_model.py. It never leaves the phone and is not in git.
 * File lines (tab separated): `U word weight`, `B a b count`, `T a b c count`. "<s>" is the start of a sentence.
 * Score = 0.6 * trigram + 0.3 * bigram + 0.1 * unigram, so a word you never wrote after this context can still be reached.
 */
class NgramWords(lines: Sequence<String>) : Ranker {
    private val words = ArrayList<String>()
    private val uniLog = ArrayList<Double>()
    private val uniP = HashMap<String, Double>()
    private val bi = HashMap<String, HashMap<String, Int>>()
    private val tri = HashMap<String, HashMap<String, Int>>()
    private var cacheKey: String? = null
    private var cache: List<Pair<String, Double>> = emptyList()

    init {
        val weights = ArrayList<Pair<String, Double>>()
        for (line in lines) {
            val p = line.split('\t')
            when (p[0]) {
                "U" -> if (p.size == 3) weights.add(p[1] to p[2].toDouble())
                "B" -> if (p.size == 4) bi.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toInt()
                "T" -> if (p.size == 5) tri.getOrPut(p[1] + " " + p[2]) { HashMap() }[p[3]] = p[4].toInt()
            }
        }
        val total = weights.sumOf { it.second }
        for ((w, c) in weights) { words.add(w); uniP[w] = c / total; uniLog.add(ln(c / total)) }
    }

    val size get() = words.size

    override fun ranked(context: List<String>): List<Pair<String, Double>> {
        val c = context.takeLast(2).map { it.lowercase() }
        val key = c.joinToString(" ")
        if (key == cacheKey) return cache
        val prev = c.lastOrNull() ?: "<s>"
        val tc = if (c.size == 2) tri[key] else null
        val bc = bi[prev]
        val wt = if (tc != null) 0.6 else 0.0
        val wb = if (bc != null) 0.3 else 0.0
        val z = wt + wb + 0.1
        val extra = HashMap<String, Double>()
        tc?.let { m -> val s = m.values.sum(); for ((w, v) in m) extra.merge(w, wt / z * v / s, Double::plus) }
        bc?.let { m -> val s = m.values.sum(); for ((w, v) in m) extra.merge(w, wb / z * v / s, Double::plus) }
        val out = ArrayList<Pair<String, Double>>(words.size)
        for (i in words.indices) {
            val w = words[i]
            out.add(w to ln(0.1 / z * uniP.getValue(w) + (extra[w] ?: 0.0)))
        }
        out.sortByDescending { it.second }
        cacheKey = key; cache = out
        return out
    }
}
