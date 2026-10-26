@file:OptIn(KitOnly::class)

package com.faka.invisible.write

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

private fun f(text: String) = Phrase.fixed(text)

/** Fixed sentences of the Write program (the stereo-pair keyboard, PLAN-OS.md). At most 7 words each. */
object TypeVoice {
    val TITLE = f("Write")
    val SUM_TITLE = f("Sum prices")
    val START = f("Write. Click on the first word.")
    val START2 = f("Hold on the second word.")
    val START_NEXT = f("Two clicks for the next two.")
    val START3 = f("Three clicks for more.")
    val STILL_HERE = f("Still in Write. Click left, hold right.")
    val MENU_DONE = f("Done")
    val MENU_DELETE = f("Delete")
    val MENU_LEAVE = f("Leave")
    val PHRASES = f("Phrases")
    val SPACE = f("Space")
    val DELETE_LETTER = f("Delete letter")
    val DELETE_WORD = f("Delete word")
    val READ = f("Read")
    val DIGITS = f("Digits")
    val SUM_START = f("Sum prices. Type a price.")
    val SUM_START2 = f("Four clicks adds it.")
    val SUM_START3 = f("Four clicks on nothing: total.")
    val SUM_NEXT = f("Next price.")
    val NO_PRICES = f("No prices")
    fun total(spoken: String) = f("Total $spoken")
    val PASSWORD = f("Password field. Not written.")
    val NO_MODEL = f("Write could not start")
    val EMPTY = f("Nothing written")
    val CONFIRM = f("Click to send. Hold to edit.")
    val TRANSLATION_MISSING = f("Translation not ready. Nothing sent.")
    val TRANSLATION_FAILED = f("Translation failed. Nothing sent.")
    val LETTERS = f("Letters")
    val WORDS = f("Words")
    val WORDS_WITH = f("Words with")
    /** "Words with a b c": the letters chosen so far, spoken one by one. */
    fun spelled(prefix: String) = f(prefix.toCharArray().joinToString(" "))
    fun wordsWith(prefix: String) = f(WORDS_WITH.text + " " + prefix.toCharArray().joinToString(" "))
}
