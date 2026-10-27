@file:OptIn(KitOnly::class)

package com.faka.invisible.claude

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

private fun f(text: String) = Phrase.fixed(text)

/** Fixed sentences of the Tools list of a window (the labels and stories come from the laptop and are content). */
object ToolVoice {
    val TOOLS = f("Tools")
    val WRITE = f("Write it, then Done.")
    val NOT_WRITTEN = f("Nothing was written.")
    val HEAR = f("Hear it again")
    val NO_TOOLS = f("No tools here.")
    /** A question for a tool that changes something: "<label>?". */
    fun sure(label: String): Phrase = Phrase.content("$label?", "en")
}
