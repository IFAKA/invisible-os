package com.faka.invisible.logickit

import com.faka.invisible.voicekit.Pacing

/**
 * How long the phone waits after an item before the next one, learned from how fast the user really clicks.
 * A sample = ms from the END of the picked item's speech to the press (negative if pressed during the speech; a press in the grace
 * window of the next item counts as the gap plus its offset into that item). Keeps the last [Pacing.REACTION_KEEP] samples.
 * gap = p90(samples) + margin, kept between the floor and the user's setting; too few samples = the user's setting. Pure.
 */
class ReactionGap(initial: List<Long> = emptyList(), private val keep: Int = Pacing.REACTION_KEEP) {
    private val samples = ArrayList<Long>()
    init { initial.takeLast(keep).forEach { samples.add(it) } }

    val size get() = samples.size

    fun add(reactionMs: Long) {
        samples.add(reactionMs)
        while (samples.size > keep) samples.removeAt(0)
    }

    /** The pause to use now. Never longer than the user's [setting], never shorter than the floor (unless the setting is). */
    fun gap(setting: Long): Long {
        if (samples.size < Pacing.REACTION_MIN_SAMPLES) return setting
        val s = samples.sorted()
        val p90 = s[(Math.ceil(0.9 * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
        return (p90 + Pacing.REACTION_MARGIN_MS).coerceIn(minOf(Pacing.REACTION_FLOOR_MS, setting), setting)
    }

    /** For saving in the preferences: comma separated. */
    fun encode() = samples.joinToString(",")

    companion object {
        fun decode(text: String): List<Long> = text.split(',').mapNotNull { it.trim().toLongOrNull() }
    }
}
