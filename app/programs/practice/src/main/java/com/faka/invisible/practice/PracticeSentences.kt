@file:OptIn(KitOnly::class)

package com.faka.invisible.practice

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the first-run practice (program-owned). At most 7 words each. */
private fun f(text: String) = Phrase.fixed(text)

object PracticeVoice {
    val TITLE get() = f("Practice")
    val INTRO get() = f("Welcome. Let's practice your button. Hold three seconds to skip. Click once now.")
    val TWO get() = f("Good. One click picks. Now click twice, fast.")
    val THREE get() = f("Good. Two clicks: the one before. Now click three times.")
    val CLOSE get() = f("Good. Three clicks: go back. Last: click, then hold. That closes.")
    val AGAIN_ONE get() = f("Click once.")
    val AGAIN_TWO get() = f("Click twice, fast.")
    val AGAIN_THREE get() = f("Click three times.")
    val AGAIN_CLOSE get() = f("Click, then hold, to close.")
}
