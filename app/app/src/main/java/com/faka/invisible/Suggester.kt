package com.faka.invisible

import java.util.Calendar

/**
 * The OS menu model's "Suggested" slot: which deep options to offer first in the hub.
 * Pure. Score = recent picks count more (half-life 14 days), picks near the same hour of day count double, and picks made in a different
 * situation count less (half per tag that differs; [TAGS]: unread messages, music playing, navigating, wired headset).
 * Lists stay in a fixed order (research: moving items breaks position memory); only this small slot adapts.
 */
object Suggester {
    const val MIN_PICKS = 2
    private const val HALF_LIFE_DAYS = 14.0
    private const val NEAR_HOURS = 2

    /** The situations the OS can see without asking. A pick remembers which were true. */
    val TAGS = listOf("unread", "playing", "navigating", "wired")

    /** Old intent names (before the menu was regrouped into apps) -> the intent that replaced them. "info:*" kept its name. */
    private val ALIASES = mapOf("games:duel" to "duel:play", "games:howto" to "duel:howto", "games:write" to "hub:write", "hub:status" to "phone:status", "hub:playing" to "hub:music")
    fun alias(intent: String): String = ALIASES[intent] ?: intent

    class Pick(val ts: Long, val intent: String, val tags: Set<String> = emptySet())

    /** 1.0 when the situation is the same now as then; every situation tag that differs halves the weight. */
    private fun likeness(a: Set<String>, b: Set<String>) = Math.pow(0.5, TAGS.count { (it in a) != (it in b) }.toDouble())

    private fun hourOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)
    private fun hourGap(a: Int, b: Int) = minOf((a - b + 24) % 24, (b - a + 24) % 24)

    /** Up to [n] of [candidates], best first. An option needs [MIN_PICKS] picks before it can be suggested. */
    fun top(history: List<Pick>, candidates: Collection<String>, now: Long, n: Int = 3, tags: Set<String> = emptySet()): List<String> {
        val hour = hourOf(now)
        val score = HashMap<String, Double>(); val picks = HashMap<String, Int>()
        for (p in history) {
            if (p.intent !in candidates || p.ts > now) continue
            val ageDays = (now - p.ts) / 86_400_000.0
            val w = Math.pow(0.5, ageDays / HALF_LIFE_DAYS) * (if (hourGap(hourOf(p.ts), hour) <= NEAR_HOURS) 2.0 else 1.0) * likeness(p.tags, tags)
            score[p.intent] = (score[p.intent] ?: 0.0) + w; picks[p.intent] = (picks[p.intent] ?: 0) + 1
        }
        return score.keys.filter { (picks[it] ?: 0) >= MIN_PICKS }
            .sortedWith(compareBy({ -(score[it] ?: 0.0) }, { it })).take(n)
    }

    /** Replay the history: how often was each pick of a candidate inside the 3 suggested from the picks before it? */
    fun hitRate(history: List<Pick>, candidates: Collection<String>, n: Int = 3): Pair<Int, Int> {
        var hits = 0; var total = 0
        val seen = ArrayList<Pick>()
        for (p in history) {
            if (p.intent in candidates) { total++; if (p.intent in top(seen, candidates, p.ts, n, p.tags)) hits++ }
            seen += p
        }
        return hits to total
    }
}
