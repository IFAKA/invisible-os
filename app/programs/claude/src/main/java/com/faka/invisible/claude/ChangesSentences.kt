@file:OptIn(KitOnly::class)

package com.faka.invisible.claude

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Show on phone (the Laptop tool). Program-owned, like ClaudeSentences.kt. */
object ChangesVoice {
    val SENT_TO_PHONE = Phrase.fixed("Sent to phone.")
    val START_PREVIEW = Phrase.fixed("Start the preview?")
    val NOT_STARTED = Phrase.fixed("The preview did not start.")
}
