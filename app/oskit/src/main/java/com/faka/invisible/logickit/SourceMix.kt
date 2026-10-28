package com.faka.invisible.logickit

/**
 * The one "mix guesses from several sources" rule (Smart Compose style, arXiv 1906.00080), used by Write's Mixer and the home ActionRanker:
 * the chance of an item is the sum over the sources of share(source) x its chance inside the source; the share of a source is its learned
 * trust ([SourceTrust]) made to sum 1. A source that had the picked item in its top 3 earns trust ([credit]). Pure.
 */
object SourceMix {
    /** [ids] with their trust ([floor] = lowest allowed trust), scaled to sum 1. */
    fun shares(ids: Collection<String>, floor: Double = 0.0, trust: (String) -> Double): Map<String, Double> {
        val w = ids.associateWith { trust(it).coerceAtLeast(floor) }
        val z = w.values.sum().takeIf { it > 0 } ?: 1.0
        return w.mapValues { it.value / z }
    }

    /** sum over sources of share x chance, in the order the tables come. */
    fun <K> mix(tables: Map<String, Map<K, Double>>, shares: Map<String, Double>): LinkedHashMap<K, Double> {
        val out = LinkedHashMap<K, Double>()
        for ((id, t) in tables) { val sh = shares.getValue(id); for ((k, p) in t) out.merge(k, sh * p, Double::plus) }
        return out
    }

    /** [m] scaled to sum 1 (empty when it sums to nothing). */
    fun <K> normalized(m: Map<K, Double>): Map<K, Double> { val z = m.values.sum(); return if (z > 0) m.mapValues { it.value / z } else emptyMap() }

    /** Items of a table, most likely first. */
    fun <K> ranks(t: Map<K, Double>): List<K> = t.entries.sortedByDescending { it.value }.map { it.key }

    /**
     * An item was picked: every source in [tables] (its ranked items) is told whether it had the item in its top 3 ([rankOf] gives the rank, -1 = none;
     * [learn] gets (source, hit)). Returns the source that ranked it best and the rank, or null when nobody had it.
     */
    fun <K> credit(tables: Map<String, List<K>>, rankOf: (List<K>) -> Int, learn: (String, Boolean) -> Unit): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for ((id, list) in tables) {
            val rank = rankOf(list)
            learn(id, rank in 0..2)
            if (rank >= 0 && (best == null || rank < best.second)) best = id to rank
        }
        return best
    }
}

/** What the person trusts each source with: hits/trials per id. trust = (hits + 1) / (trials + 2), starting at 0.5. Text form "id=hits/trials;..." for one prefs string. */
class SourceTrust(initial: String?, private val save: (String) -> Unit = {}) {
    private val hits = HashMap<String, Int>(); private val trials = HashMap<String, Int>()
    init { initial?.split(';')?.forEach { e -> val (k, v) = e.split('=').let { it.getOrNull(0) to it.getOrNull(1) }; val (h, t) = v?.split('/')?.let { it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)?.toIntOrNull() } ?: (null to null); if (k != null && h != null && t != null) set(k, h, t) } }
    fun trust(id: String) = ((hits[id] ?: 0) + 1.0) / ((trials[id] ?: 0) + 2.0)
    @Synchronized fun scored(id: String, hit: Boolean) { trials.merge(id, 1, Int::plus); if (hit) hits.merge(id, 1, Int::plus); save(text()) }
    fun set(id: String, hit: Int, trial: Int) { hits[id] = hit; trials[id] = trial }
    fun entries(): List<Triple<String, Int, Int>> = trials.keys.sorted().map { Triple(it, hits[it] ?: 0, trials.getValue(it)) }
    fun text() = entries().joinToString(";") { "${it.first}=${it.second}/${it.third}" }
}
