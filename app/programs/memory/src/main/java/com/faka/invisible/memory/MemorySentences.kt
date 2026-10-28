@file:OptIn(KitOnly::class)

package com.faka.invisible.memory

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Memory. They live here, in the program, not in the Voice Kit. At most 7 words each. */
private fun f(text: String) = Phrase.fixed(text)

object MemoryVoice {
    /** The menu label. */
    val TITLE get() = f("Memory")
    val INTRO get() = f("Memory. Listen, then copy the tones.")
    val HOW get() = f("Low tone: click. High tone: click twice.")
    val LEAVE get() = f("Hold three seconds to leave.")
    val LOADING get() = f("One moment.")
    val ROUND_OK get() = f("Good. Listen.")
    fun over(score: Int, best: Int) = f(if (score >= best && score > 0) "Wrong. New best, $score." else "Wrong. Score $score. Best $best.")
    val ALL: List<Phrase> get() = listOf(TITLE, INTRO, HOW, LEAVE, LOADING, ROUND_OK)
}
