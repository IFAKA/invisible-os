@file:OptIn(KitOnly::class)

package com.faka.invisible.habits

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Habits tool. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object HabitsVoice {
    /** The menu label. */
    val TITLE get() = f("Habits")
    val INTRO get() = f("Habits. Click: yes. Two clicks: no.")
    val YES get() = f("Yes.")
    val NO get() = f("No.")
    val DONE get() = f("All done.")
    fun habit(name: String, streak: Int) = f("$name, streak $streak.")
}
