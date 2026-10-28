@file:OptIn(KitOnly::class)

package com.faka.invisible.counter

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Counter tool. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object CounterVoice {
    /** The menu label. */
    val TITLE get() = f("Counter")
    val HELP get() = f("Click: add. Two clicks: undo. Hold: total.")
    val NEXT get() = f("Four clicks: next counter.")
    /** A number alone (every fifth one). */
    fun number(n: Int) = f("$n")
    /** The counter and its total. */
    fun total(name: String, n: Int) = f("$name, $n.")
}
