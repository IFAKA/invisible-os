@file:OptIn(KitOnly::class)

package com.faka.invisible.reader

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

private fun f(text: String) = Phrase.fixed(text)

/** Fixed sentences of the Reader tool. The text it reads is content, not here. */
object ReaderVoice {
    val TITLE get() = f("Reader")
    val NOTHING get() = f("Nothing to read. Share a text to Invisible reader.")
    val SAVED get() = f("Saved for Reader.")
    val END get() = f("End of text.")
    /** Said when a text comes back where the person stopped. */
    fun resume(n: Int, of: Int) = f("Back at part $n of $of.")
    fun start(of: Int) = f(if (of == 1) "One part." else "$of parts.")
}
