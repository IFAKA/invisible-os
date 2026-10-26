@file:OptIn(KitOnly::class)

package com.faka.invisible.guard

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Guard. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

/** Guard: a gate guard who strikes on the fourth step. Plain English, at most 7 words per sentence, one idea each. */
object GuardVoice {
    /** The menu label. */
    val TITLE = f("Guard")
    val INTRO = f("Guard. Click on the fourth step.")
    val GATE = f("Guard. You are a guard at a gate.")
    val LISTEN = f("Listen.")
    val CLICK_FOURTH = f("Click on the fourth step.")
    val GOOD_AGAIN = f("Good. Again.")
    val GOOD = f("Good.")
    val TOO_EARLY = f("Too early. Wait for the fourth.")
    val TOO_LATE = f("Too late. Click sooner.")
    val CLICK_PING = f("Click when you hear the ping.")
    val LEAVE = f("Hold three seconds to leave.")
    val CLICK_BEAT = f("Click with the beat.")
    val NO_PING = f("Now without the ping.")
    val KNIGHT = f("A knight. Two strikes.")
    val CLICK_FOUR_FIVE = f("Click on four and five.")
    val BELL = f("A bell. Do not click.")
    val RIGHT_WAIT = f("Right. Bell means wait.")
    val NO_WAIT = f("No. Bell means wait.")
    val READY = f("Ready. Three lives. Begin.")
    val PRESS = f("Press your button to play.")
    val FAST = f("Fast thief.")
    val OGRE = f("Ogre. Click only on the big thud.")
    val LAST_LIFE = f("Last life.")
    val LOADING = f("One moment.")
    fun over(score: Int, best: Int) = f(if (score >= best && score > 0) "Score $score. New best." else "Score $score. Best $best.")
    val ALL: List<Phrase> = listOf(INTRO, GATE, LISTEN, CLICK_FOURTH, GOOD_AGAIN, GOOD, TOO_EARLY, TOO_LATE, CLICK_PING, LEAVE, CLICK_BEAT, NO_PING,
        KNIGHT, CLICK_FOUR_FIVE, BELL, RIGHT_WAIT, NO_WAIT, READY, PRESS, FAST, OGRE, LAST_LIFE, LOADING)
}
