@file:OptIn(KitOnly::class)

package com.faka.invisible.reaction

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Reaction. They live here, in the program, not in the Voice Kit. At most 7 words each. */
private fun f(text: String) = Phrase.fixed(text)

object ReactionVoice {
    /** The menu label. */
    val TITLE get() = f("Reaction")
    val INTRO get() = f("Reaction. Wait for the beep, then click fast.")
    val LEAVE get() = f("Hold three seconds to leave.")
    val LOADING get() = f("One moment.")
    val EARLY get() = f("Too early. Wait for the beep.")
    val TOO_SLOW get() = f("Too slow.")
    fun time(ms: Int) = f("$ms.")
    /** [best] is the best average so far in ms (0 = none yet). */
    fun over(avg: Int, best: Int) = f(if (best == 0 || avg < best) "Average $avg milliseconds. New best." else "Average $avg milliseconds. Best $best.")
    val ALL: List<Phrase> get() = listOf(TITLE, INTRO, LEAVE, LOADING, EARLY, TOO_SLOW)
}
