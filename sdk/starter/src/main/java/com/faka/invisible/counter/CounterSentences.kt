@file:OptIn(KitOnly::class)

package com.faka.invisible.counter

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

private fun f(text: String) = Phrase.fixed(text)

/**
 * Every fixed sentence of the program lives here and nowhere else. At most 7 words each: people hear them, they do not read them.
 * To change what the program says, change this file.
 */
object CounterVoice {
    /** The line in the menu. */
    val TITLE = f("Counter")
    val WELCOME = f("Counter. Click to add one.")
    val HOW = f("Two clicks: start again.")
    val LEAVE = f("Hold three seconds to leave.")
    val ZERO = f("Zero.")
    fun count(n: Int) = f("$n")
}
