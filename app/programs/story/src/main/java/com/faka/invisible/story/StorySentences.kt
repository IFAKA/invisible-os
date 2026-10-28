@file:OptIn(KitOnly::class)

package com.faka.invisible.story

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Story game. They live here, in the program. At most 7 words each. */
private fun f(text: String) = Phrase.fixed(text)

object StoryVoice {
    val TITLE get() = f("Story")
    val INTRO get() = f("Story. Click when you hear your choice.")
    val LEAVE get() = f("Hold three seconds to leave.")
    val LOADING get() = f("One moment.")
    val BACK get() = f("Welcome back.")
    val PICK get() = f("Pick a story.")
    val THE_END get() = f("The end.")
    val ALL: List<Phrase> get() = listOf(TITLE, INTRO, LEAVE, LOADING, BACK, PICK, THE_END)
}
