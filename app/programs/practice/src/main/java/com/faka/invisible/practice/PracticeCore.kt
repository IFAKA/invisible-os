package com.faka.invisible.practice

import com.faka.invisible.voicekit.Gesture

/** The lesson, one mechanic at a time. Pure: which step comes after which gesture. */
enum class PracticeStep { ONE, TWO, THREE, CLOSE }

object PracticeCore {
    /** The gesture that passes each step. CLOSE (click then hold) is caught by the OS, which stops the program. */
    fun wanted(step: PracticeStep): Gesture? = when (step) {
        PracticeStep.ONE -> Gesture.SINGLE
        PracticeStep.TWO -> Gesture.DOUBLE
        PracticeStep.THREE -> Gesture.TRIPLE
        PracticeStep.CLOSE -> null
    }

    /** The next step after [g], or null when [g] is not what this step teaches. */
    fun next(step: PracticeStep, g: Gesture?): PracticeStep? =
        if (g != null && g == wanted(step)) PracticeStep.values().getOrNull(step.ordinal + 1) else null

    /** True when [g] passes [step] (also the last step, which has no next one). */
    fun passes(step: PracticeStep, g: Gesture?) = g != null && g == wanted(step)

    /** After this many unanswered prompts the lesson ends by itself. */
    const val MAX_IDLE = 3
}
