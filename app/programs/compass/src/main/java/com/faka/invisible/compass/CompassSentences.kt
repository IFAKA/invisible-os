@file:OptIn(KitOnly::class)

package com.faka.invisible.compass

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Compass. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object CompassVoice {
    /** The menu label. */
    val TITLE = f("Compass")
    val INTRO = f("Compass. Click: heading. Two clicks: find north.")
    val LEAVE = f("Hold three seconds to leave.")
    val FINDING = f("Finding north.")
    val STOPPED = f("Stopped.")
    val WAIT = f("One moment.")
    val NO_SENSOR = f("This phone has no compass.")
    val UNSURE = f("Not sure. Move the phone in a figure eight.")
    fun heading(name: String, degrees: Int) = f("$name, $degrees degrees.")
}
