@file:OptIn(KitOnly::class)

package com.faka.invisible.compass

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Compass. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object CompassVoice {
    /** The menu label. */
    val TITLE get() = f("Compass")
    val INTRO get() = f("Compass. Click: heading. Two clicks: find north.")
    val LEAVE get() = f("Hold three seconds to leave.")
    val FINDING get() = f("Finding north.")
    val STOPPED get() = f("Stopped.")
    val WAIT get() = f("One moment.")
    val NO_SENSOR get() = f("This phone has no compass.")
    val UNSURE get() = f("Not sure. Move the phone in a figure eight.")
    fun heading(name: String, degrees: Int) = f("$name, $degrees degrees.")
}
