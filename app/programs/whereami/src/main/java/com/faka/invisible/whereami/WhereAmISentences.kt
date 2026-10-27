@file:OptIn(KitOnly::class)

package com.faka.invisible.whereami

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Where am I. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object WhereVoice {
    val TITLE get() = f("Where am I")
    val WAIT get() = f("Finding where you are.")
    val NOT_FOUND get() = f("Location not found.")
    val NEED_LOCATION get() = f("Location is off. Allow location in the setup.")
}
