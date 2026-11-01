@file:OptIn(KitOnly::class)

package com.faka.invisible.practice

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.PressHint

/** Fixed sentences of the first-run practice (program-owned). At most 7 words each. */
private fun f(text: String) = Phrase.fixed(text)

object PracticeVoice {
    val TITLE get() = f("Practice")
    val INTRO get() = f("Welcome. Let's practice your button. Hold three seconds to skip. Click once now.").asks(PressHint.click(1))
    val TWO get() = f("Good. One click picks. Now click twice, fast.").asks(PressHint.click(2))
    val THREE get() = f("Good. Two clicks: the one before. Now click three times.").asks(PressHint.click(3))
    val CLOSE get() = f("Good. Three clicks: go back. Last: click, then hold. That closes the app. Now hold once to wake me.").asks(PressHint.HOLD)
    val AGAIN_ONE get() = f("Click once.").asks(PressHint.click(1))
    val AGAIN_TWO get() = f("Click twice, fast.").asks(PressHint.click(2))
    val AGAIN_THREE get() = f("Click three times.").asks(PressHint.click(3))
    val AGAIN_CLOSE get() = f("Click, then hold, to close. Now hold once to wake me.").asks(PressHint.CLICK_HOLD)
}
