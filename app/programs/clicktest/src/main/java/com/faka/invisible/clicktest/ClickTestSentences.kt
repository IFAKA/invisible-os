@file:OptIn(KitOnly::class)

package com.faka.invisible.clicktest

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Click test. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

/** Plain English, at most 7 words per sentence. */
object ClickTestVoice {
    /** The menu label. */
    val TITLE get() = f("Click test")
    val INTRO get() = f("Click test. Click on the high bell. Hold three seconds to leave.")
    val NO_CLICKS get() = f("No clicks heard. Try again later.")
    val TOO_FEW get() = f("Not enough clicks to measure.")
    val SAVED get() = f("Saved.")

    /** "You click 180 milliseconds late." / "You click on time." */
    fun lateness(meanMs: Int) = f(when {
        meanMs in -20..20 -> "You click on time."
        meanMs > 0 -> "You click $meanMs milliseconds late."
        else -> "You click ${-meanMs} milliseconds early."
    })

    /** One word for the spread. */
    fun spread(sdMs: Int) = f(when {
        sdMs < 50 -> "Very steady."
        sdMs < 90 -> "Steady."
        sdMs < 140 -> "A little uneven."
        else -> "Uneven."
    })
}
