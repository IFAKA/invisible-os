@file:OptIn(KitOnly::class)

package com.faka.invisible.breathe

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Breathe tool. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object BreatheVoice {
    /** The menu label. */
    val TITLE get() = f("Breathe")
    val INTRO get() = f("Breathe. Click: start. Two clicks: sleep timer.")
    val LEAVE get() = f("Three clicks: leave.")
    val BEGIN get() = f("Breathe with the sounds. Rising: in.")
    val DONE get() = f("Well done.")
    val SLEEP get() = f("Sleep timer. The music fades in one minute.")
    val SLEEP_OFF get() = f("Sleep timer stopped.")
    val NO_MUSIC get() = f("Music is quiet. Timer stopped.")
    fun rounds(n: Int) = f("$n rounds.")
}
