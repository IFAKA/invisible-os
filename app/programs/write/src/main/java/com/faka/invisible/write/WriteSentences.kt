@file:OptIn(KitOnly::class)

package com.faka.invisible.write

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

private fun f(text: String) = Phrase.fixed(text)

/** Fixed sentences of the Write program (the stereo-pair keyboard, PLAN-OS.md). At most 7 words each. */
object TypeVoice {
    val TITLE get() = f("Write")
    val SUM_TITLE get() = f("Sum prices")
    val START get() = f("Write. Click on the first word.")
    val START2 get() = f("Hold on the second word.")
    val START_NEXT get() = f("Two clicks for the next two.")
    val START3 get() = f("Three clicks for more.")
    val STILL_HERE get() = f("Still in Write. Click left, hold right.")
    val MENU_DONE get() = f("Done")
    val MENU_DELETE get() = f("Delete")
    val MENU_LEAVE get() = f("Leave")
    val PHRASES get() = f("Phrases")
    val SPACE get() = f("Space")
    val DELETE_LETTER get() = f("Delete letter")
    val DELETE_WORD get() = f("Delete word")
    val READ get() = f("Read")
    val DIGITS get() = f("Digits")
    val SUM_START get() = f("Sum prices. Type a price.")
    val SUM_START2 get() = f("Four clicks adds it.")
    val SUM_START3 get() = f("Four clicks on nothing: total.")
    val SUM_NEXT get() = f("Next price.")
    val NO_PRICES get() = f("No prices")
    fun total(spoken: String) = f("Total $spoken")
    val PASSWORD get() = f("Password field. Not written.")
    val NO_MODEL get() = f("Write could not start")
    val EMPTY get() = f("Nothing written")
    val CONFIRM get() = f("Click to send. Hold to edit.")
    val TRANSLATION_MISSING get() = f("Translation not ready. Nothing sent.")
    val TRANSLATION_FAILED get() = f("Translation failed. Nothing sent.")
    val LETTERS get() = f("Letters")
    val WORDS get() = f("Words")
    val WORDS_WITH get() = f("Words with")
    /** "Words with a b c": the letters chosen so far, spoken one by one. */
    fun spelled(prefix: String) = f(letterByLetter(prefix))
    fun wordsWith(prefix: String) = WORDS_WITH + f(": " + letterByLetter(prefix))
    /** "A, B, C": capitals and commas make the voice say each letter alone, with a short pause (a bare "a b c" runs together and "a" is read as a word). */
    private fun letterByLetter(prefix: String) = prefix.uppercase().toCharArray().joinToString(", ")
}
