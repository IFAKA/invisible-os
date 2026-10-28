@file:OptIn(KitOnly::class)

package com.faka.invisible.quiz

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Quiz. They live here, in the program, not in the Voice Kit. At most 7 words each. */
private fun f(text: String) = Phrase.fixed(text)

object QuizVoice {
    /** The menu label. */
    val TITLE get() = f("Quiz")
    val INTRO get() = f("Quiz. Ten questions, three answers each.")
    val HOW get() = f("Click when you hear the right one.")
    val LEAVE get() = f("Hold three seconds to leave.")
    val LOADING get() = f("One moment.")
    val RIGHT get() = f("Right.")
    val TOO_SLOW get() = f("No answer.")
    fun wrong(answer: Phrase) = Phrase.join(listOf(f("Wrong. It was"), answer))
    fun over(score: Int, best: Int) = f(if (score >= best && score > 0) "Done. $score of 10. New best." else "Done. $score of 10. Best $best.")
    val ALL: List<Phrase> get() = listOf(TITLE, INTRO, HOW, LEAVE, LOADING, RIGHT, TOO_SLOW)
}
